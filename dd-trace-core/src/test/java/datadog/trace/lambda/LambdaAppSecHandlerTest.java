package datadog.trace.lambda;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.core.DDCoreJavaSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LambdaAppSecHandlerTest extends DDCoreJavaSpecification {

  static boolean originalAppSecActive;
  static AgentTracer.TracerAPI originalTracer;

  @BeforeAll
  static void saveState() {
    originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    originalTracer = AgentTracer.get();
  }

  @AfterAll
  static void restoreAppSecState() {
    ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
  }

  @BeforeEach
  void enableAppSec() {
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void resetTracer() {
    AgentTracer.forceRegister(originalTracer);
    LambdaAppSecHandler.setCurrentTriggerType(null);
  }

  // ============================================================================
  // processRequestStart — guard tests
  // ============================================================================

  @Test
  void processRequestStartReturnsNullWhenAppSecIsDisabled() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    ByteArrayInputStream event = createInputStream("{\"test\": \"data\"}");
    assertNull(LambdaAppSecHandler.processRequestStart(event));
  }

  @Test
  void processRequestStartReturnsNullForNonByteArrayInputStream() {
    assertNull(LambdaAppSecHandler.processRequestStart("not a stream"));
  }

  @Test
  void processRequestStartReturnsNullForNullEvent() {
    assertNull(LambdaAppSecHandler.processRequestStart(null));
  }

  @Test
  void processRequestStartReturnsNullForOversizedEvent() {
    int maxSize = Config.get().getAppSecBodyParsingSizeLimit();
    char[] chars = new char[maxSize + 1];
    java.util.Arrays.fill(chars, 'x');
    ByteArrayInputStream event = createInputStream(new String(chars));
    assertNull(LambdaAppSecHandler.processRequestStart(event));
  }

  @Test
  void processRequestStartReturnsNullForZeroSizeEvent() {
    ByteArrayInputStream event = createInputStream("");
    assertNull(LambdaAppSecHandler.processRequestStart(event));
  }

  @Test
  void processRequestStartReturnsNullForMalformedJson() {
    ByteArrayInputStream event = createInputStream("{invalid json");
    assertNull(LambdaAppSecHandler.processRequestStart(event));
  }

  @Test
  void streamCanBeReadMultipleTimesAfterProcessing() throws IOException {
    String jsonData = "{\"test\": \"data\", \"requestContext\": {\"httpMethod\": \"GET\"}}";
    ByteArrayInputStream event = createInputStream(jsonData);
    LambdaAppSecHandler.processRequestStart(event);
    event.reset();
    byte[] bytes = new byte[event.available()];
    event.read(bytes);
    String content = new String(bytes, StandardCharsets.UTF_8);
    assertEquals(jsonData, content);
  }

  // ============================================================================
  // Trigger Type Detection Tests
  // ============================================================================

  @Test
  void detectsApiGatewayV1RestTriggerType() {
    Map<String, Object> event =
        mapOf("requestContext", mapOf("httpMethod", "GET", "requestId", "abc123"));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST, triggerType);
  }

  @Test
  void detectsApiGatewayV2HttpTriggerType() {
    Map<String, Object> event =
        mapOf(
            "requestContext",
            mapOf(
                "http", mapOf("method", "POST", "path", "/api"), "domainName", "api.example.com"));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_HTTP, triggerType);
  }

  @Test
  void detectsLambdaFunctionUrlTriggerType() {
    Map<String, Object> event =
        mapOf(
            "requestContext",
            mapOf(
                "http",
                mapOf("method", "GET", "path", "/"),
                "domainName",
                "xyz123.lambda-url.us-east-1.on.aws"));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.LAMBDA_URL, triggerType);
  }

  @Test
  void detectsAlbTriggerTypeWithoutMultiValueHeaders() {
    Map<String, Object> event =
        mapOf(
            "httpMethod",
            "GET",
            "path",
            "/",
            "requestContext",
            mapOf("elb", mapOf("targetGroupArn", "arn:aws:...")));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.ALB, triggerType);
  }

  @Test
  void detectsAlbTriggerTypeWithMultiValueHeaders() {
    Map<String, Object> event =
        mapOf(
            "httpMethod",
            "GET",
            "path",
            "/",
            "multiValueHeaders",
            mapOf("accept", Arrays.asList("text/html", "application/json")),
            "requestContext",
            mapOf("elb", mapOf("targetGroupArn", "arn:aws:...")));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.ALB_MULTI_VALUE, triggerType);
  }

  @Test
  void detectsWebSocketTriggerTypeWithRouteKey() {
    Map<String, Object> event =
        mapOf("requestContext", mapOf("connectionId", "conn-123", "routeKey", "$connect"));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET, triggerType);
  }

  @Test
  void detectsWebSocketTriggerTypeWithEventType() {
    Map<String, Object> event =
        mapOf("requestContext", mapOf("connectionId", "conn-456", "eventType", "CONNECT"));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET, triggerType);
  }

  @Test
  void detectsUnknownTriggerTypeForUnrecognizedEvents() {
    Map<String, Object> event = mapOf("someUnknownField", "value");

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.UNKNOWN, triggerType);
  }

  @Test
  void detectsUnknownTriggerTypeForEmptyRequestContext() {
    Map<String, Object> event = mapOf("requestContext", mapOf());

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.UNKNOWN, triggerType);
  }

  @Test
  void detectsLambdaUrlWhenHttpPresentButNoDomainName() {
    Map<String, Object> event =
        mapOf("requestContext", mapOf("http", mapOf("method", "GET", "path", "/ambiguous")));

    LambdaAppSecHandler.LambdaTriggerType triggerType =
        LambdaAppSecHandler.detectTriggerType(event);

    assertEquals(LambdaAppSecHandler.LambdaTriggerType.LAMBDA_URL, triggerType);
  }

  // ============================================================================
  // Data Extraction Tests with Mocked Callbacks
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  void extractsApiGatewayV1RestDataCorrectly() {
    String eventJson =
        "{"
            + "\"path\": \"/api/users/123\","
            + "\"httpMethod\": \"POST\","
            + "\"headers\": {\"Content-Type\": \"application/json\", \"Authorization\": \"Bearer token123\"},"
            + "\"pathParameters\": {\"userId\": \"123\"},"
            + "\"body\": \"{\\\"name\\\": \\\"John\\\"}\","
            + "\"requestContext\": {"
            + "  \"httpMethod\": \"POST\","
            + "  \"requestId\": \"req-123\","
            + "  \"identity\": {\"sourceIp\": \"192.168.1.100\"}"
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    String[] capturedSourceIp = {null};
    int[] capturedSourcePort = {-1};
    Map[] capturedPathParams = {null};
    Object[] capturedBody = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onHeader(capturedHeaders::put)
            .onSocketAddress(
                (ip, port) -> {
                  capturedSourceIp[0] = ip;
                  capturedSourcePort[0] = port;
                })
            .onPathParams(params -> capturedPathParams[0] = params)
            .onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertInstanceOf(TagContext.class, result);
    assertEquals("POST", capturedMethod[0]);
    assertEquals("/api/users/123", capturedPath[0]);
    assertEquals("application/json", capturedHeaders.get("Content-Type"));
    assertEquals("Bearer token123", capturedHeaders.get("Authorization"));
    assertEquals("192.168.1.100", capturedSourceIp[0]);
    assertEquals(0, capturedSourcePort[0]);
    assertNotNull(capturedPathParams[0]);
    assertEquals("123", capturedPathParams[0].get("userId"));
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("John", ((Map<?, ?>) capturedBody[0]).get("name"));
  }

  @Test
  void extractsApiGatewayV2HttpDataCorrectly() {
    String eventJson =
        "{"
            + "\"version\": \"2.0\","
            + "\"headers\": {\"content-type\": \"application/json\", \"x-custom-header\": \"custom-value\"},"
            + "\"cookies\": [\"session=abc123\", \"user=john\"],"
            + "\"pathParameters\": {\"id\": \"456\"},"
            + "\"body\": \"test body\","
            + "\"requestContext\": {"
            + "  \"http\": {\"method\": \"PUT\", \"path\": \"/api/items/456\", \"sourceIp\": \"10.0.0.50\", \"sourcePort\": 54321},"
            + "  \"domainName\": \"api.example.com\""
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    String[] capturedSourceIp = {null};
    int[] capturedSourcePort = {-1};
    Map[] capturedPathParams = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onHeader(capturedHeaders::put)
            .onSocketAddress(
                (ip, port) -> {
                  capturedSourceIp[0] = ip;
                  capturedSourcePort[0] = port;
                })
            .onPathParams(params -> capturedPathParams[0] = params));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("PUT", capturedMethod[0]);
    assertEquals("/api/items/456", capturedPath[0]);
    assertEquals("application/json", capturedHeaders.get("content-type"));
    assertEquals("custom-value", capturedHeaders.get("x-custom-header"));
    assertEquals("session=abc123; user=john", capturedHeaders.get("cookie"));
    assertEquals("10.0.0.50", capturedSourceIp[0]);
    assertEquals(54321, capturedSourcePort[0]);
    assertNotNull(capturedPathParams[0]);
    assertEquals("456", capturedPathParams[0].get("id"));
  }

  @Test
  void extractsLambdaFunctionUrlDataCorrectly() {
    String eventJson =
        "{"
            + "\"version\": \"2.0\","
            + "\"headers\": {\"host\": \"xyz.lambda-url.us-east-1.on.aws\"},"
            + "\"requestContext\": {"
            + "  \"http\": {\"method\": \"GET\", \"path\": \"/function/path\", \"sourceIp\": \"1.2.3.4\"},"
            + "  \"domainName\": \"xyz.lambda-url.us-east-1.on.aws\""
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("GET", capturedMethod[0]);
    assertEquals("/function/path", capturedPath[0]);
  }

  @Test
  void extractsAlbDataCorrectly() {
    String eventJson =
        "{"
            + "\"path\": \"/alb/test\","
            + "\"httpMethod\": \"DELETE\","
            + "\"headers\": {\"x-forwarded-for\": \"203.0.113.42\", \"user-agent\": \"curl/7.64.1\"},"
            + "\"requestContext\": {"
            + "  \"elb\": {\"targetGroupArn\": \"arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/tg/50dc6c495c0c9188\"}"
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    String[] capturedSourceIp = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onSocketAddress((ip, port) -> capturedSourceIp[0] = ip));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("DELETE", capturedMethod[0]);
    assertEquals("/alb/test", capturedPath[0]);
    assertEquals("203.0.113.42", capturedSourceIp[0]);
  }

  @Test
  void extractsAlbMultiValueHeadersCorrectly() {
    String eventJson =
        "{"
            + "\"path\": \"/test\","
            + "\"httpMethod\": \"GET\","
            + "\"multiValueHeaders\": {\"accept\": [\"text/html\", \"application/json\"], \"x-custom\": [\"value1\", \"value2\"]},"
            + "\"requestContext\": {\"elb\": {\"targetGroupArn\": \"arn:aws:...\"}}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader(capturedHeaders::put));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("text/html, application/json", capturedHeaders.get("accept"));
    assertEquals("value1, value2", capturedHeaders.get("x-custom"));
  }

  @Test
  void handlesMultiValueHeadersWithEmptyList() {
    String eventJson =
        "{"
            + "\"path\": \"/test\","
            + "\"httpMethod\": \"GET\","
            + "\"multiValueHeaders\": {\"accept\": [], \"x-custom\": [\"value1\"]},"
            + "\"requestContext\": {\"elb\": {\"targetGroupArn\": \"arn:aws:...\"}}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader(capturedHeaders::put));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("", capturedHeaders.get("accept"));
    assertEquals("value1", capturedHeaders.get("x-custom"));
  }

  @Test
  void extractsWebSocketDataCorrectly() {
    String eventJson =
        "{"
            + "\"requestContext\": {"
            + "  \"routeKey\": \"$connect\","
            + "  \"connectionId\": \"conn-abc123\","
            + "  \"identity\": {\"sourceIp\": \"192.168.0.100\"}"
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    String[] capturedSourceIp = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onSocketAddress((ip, port) -> capturedSourceIp[0] = ip));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("WEBSOCKET", capturedMethod[0]);
    assertEquals("$connect", capturedPath[0]);
    assertEquals("192.168.0.100", capturedSourceIp[0]);
  }

  @Test
  void handlesBase64EncodedBodyCorrectly() {
    String originalBody = "This is test data";
    String base64Body = Base64.getEncoder().encodeToString(originalBody.getBytes());
    String eventJson =
        "{"
            + "\"body\": \""
            + base64Body
            + "\","
            + "\"isBase64Encoded\": true,"
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object[] capturedBody = {null};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals(originalBody, capturedBody[0]);
  }

  @Test
  void handlesNullBodyCorrectly() {
    ByteArrayInputStream event =
        createInputStream("{\"body\": null, \"requestContext\": {\"httpMethod\": \"GET\"}}");

    String[] capturedBody = {"NOT_CALLED"};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = String.valueOf(body)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("NOT_CALLED", capturedBody[0]);
  }

  @Test
  void handlesEmptyBodyCorrectly() {
    ByteArrayInputStream event =
        createInputStream("{\"body\": \"\", \"requestContext\": {\"httpMethod\": \"POST\"}}");

    Object[] capturedBody = {null};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("", capturedBody[0]);
  }

  @Test
  void handlesPathWithQueryStringCorrectly() {
    String eventJson =
        "{"
            + "\"path\": \"/api/users?id=123&filter=active\","
            + "\"requestContext\": {\"httpMethod\": \"GET\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedPath = {null};
    String[] capturedQuery = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedPath[0] = uri.path();
                  capturedQuery[0] = uri.query();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("/api/users", capturedPath[0]);
    assertEquals("id=123&filter=active", capturedQuery[0]);
  }

  @Test
  void extractsSchemeAndPortFromXForwardedHeaders() {
    String eventJson =
        "{"
            + "\"path\": \"/api/test\","
            + "\"headers\": {\"x-forwarded-proto\": \"http\", \"x-forwarded-port\": \"8080\"},"
            + "\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-123\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedScheme = {null};
    int[] capturedPort = {-1};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedScheme[0] = uri.scheme();
                  capturedPort[0] = uri.port();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("http", capturedScheme[0]);
    assertEquals(8080, capturedPort[0]);
  }

  @Test
  void fallsBackToHttps443WhenXForwardedHeadersAreAbsent() {
    String eventJson =
        "{"
            + "\"path\": \"/api/test\","
            + "\"headers\": {},"
            + "\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-123\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedScheme = {null};
    int[] capturedPort = {-1};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedScheme[0] = uri.scheme();
                  capturedPort[0] = uri.port();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("https", capturedScheme[0]);
    assertEquals(443, capturedPort[0]);
  }

  @Test
  void handlesInvalidXForwardedPortGracefully() {
    String eventJson =
        "{"
            + "\"path\": \"/api/test\","
            + "\"headers\": {\"x-forwarded-proto\": \"https\", \"x-forwarded-port\": \"not-a-number\"},"
            + "\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-123\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedScheme = {null};
    int[] capturedPort = {-1};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedScheme[0] = uri.scheme();
                  capturedPort[0] = uri.port();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("https", capturedScheme[0]);
    assertEquals(443, capturedPort[0]);
  }

  @Test
  void handlesInvalidBase64BodyGracefully() {
    String eventJson =
        "{"
            + "\"body\": \"not-valid-base64\","
            + "\"isBase64Encoded\": true,"
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedBody = {"NOT_CALLED"};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = String.valueOf(body)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("NOT_CALLED", capturedBody[0]);
  }

  @Test
  void handlesBase64DecodedEmptyStringBody() {
    String base64Empty = Base64.getEncoder().encodeToString("".getBytes());
    String eventJson =
        "{"
            + "\"body\": \""
            + base64Empty
            + "\","
            + "\"isBase64Encoded\": true,"
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object[] capturedBody = {null};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("", capturedBody[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void handlesBodyWithSpecialCharacters() {
    String eventJson =
        "{"
            + "\"body\": \"{\\\"text\\\": \\\"Hello \\u4e16\\u754c \\uD83C\\uDF0D\\\"}\","
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object[] capturedBody = {null};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("Hello 世界 🌍", ((Map<?, ?>) capturedBody[0]).get("text"));
  }

  // ============================================================================
  // Generic Data Extraction Tests
  // ============================================================================

  @Test
  void extractsDataFromUnknownTriggerTypeUsingGenericExtraction() {
    String eventJson =
        "{"
            + "\"path\": \"/generic/path\","
            + "\"httpMethod\": \"PATCH\","
            + "\"headers\": {\"x-custom-header\": \"generic-value\"},"
            + "\"unknownField\": \"should be ignored\","
            + "\"requestContext\": {\"identity\": {\"sourceIp\": \"203.0.113.1\"}}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    String[] capturedSourceIp = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onHeader(capturedHeaders::put)
            .onSocketAddress((ip, port) -> capturedSourceIp[0] = ip));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("PATCH", capturedMethod[0]);
    assertEquals("/generic/path", capturedPath[0]);
    assertEquals("generic-value", capturedHeaders.get("x-custom-header"));
    assertEquals("203.0.113.1", capturedSourceIp[0]);
  }

  @Test
  void extractsDataFromUnknownTriggerWithHttpInRequestContext() {
    String eventJson =
        "{"
            + "\"requestContext\": {"
            + "  \"http\": {\"method\": \"OPTIONS\", \"path\": \"/options/path\", \"sourceIp\": \"198.51.100.50\"}"
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    String[] capturedSourceIp = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onSocketAddress((ip, port) -> capturedSourceIp[0] = ip));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("OPTIONS", capturedMethod[0]);
    assertEquals("/options/path", capturedPath[0]);
    assertEquals("198.51.100.50", capturedSourceIp[0]);
  }

  @Test
  void handlesCookiesMergingWithExistingCookieHeader() {
    String eventJson =
        "{"
            + "\"headers\": {\"cookie\": \"existing=value\"},"
            + "\"cookies\": [\"new=cookie1\", \"another=cookie2\"],"
            + "\"requestContext\": {\"http\": {\"method\": \"GET\", \"path\": \"/\"}}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader(capturedHeaders::put));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("existing=value; new=cookie1; another=cookie2", capturedHeaders.get("cookie"));
  }

  @Test
  void handlesEmptyCookiesArrayCorrectly() {
    String eventJson =
        "{"
            + "\"headers\": {\"content-type\": \"application/json\"},"
            + "\"cookies\": [],"
            + "\"requestContext\": {\"http\": {\"method\": \"GET\", \"path\": \"/\"}}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader(capturedHeaders::put));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertFalse(capturedHeaders.containsKey("cookie"));
  }

  // ============================================================================
  // processRequestEnd Tests
  // ============================================================================

  @Test
  void processRequestEndDoesNothingWhenSpanIsNull() {
    LambdaAppSecHandler.processRequestEnd(null);
    // no exception expected
  }

  @Test
  void processRequestEndDoesNothingWhenAppSecIsDisabled() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpan span = mock(AgentSpan.class);

    LambdaAppSecHandler.processRequestEnd(span);

    verifyNoInteractions(span);
  }

  @Test
  void processRequestEndDoesNothingWhenSpanHasNoRequestContext() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(null);
    LambdaAppSecHandler.processRequestEnd(span);
    // no exception expected
  }

  @Test
  @SuppressWarnings("unchecked")
  void processRequestEndInvokesRequestEndedCallbackWithRequestContext() {
    Object mockAppSecContext = new Object();
    RequestContext mockRequestContext = mock(RequestContext.class);
    when(mockRequestContext.getData(RequestContextSlot.APPSEC)).thenReturn(mockAppSecContext);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mockRequestContext);

    boolean[] callbackInvoked = {false};
    RequestContext[] capturedContext = {null};
    AgentSpan[] capturedSpan = {null};

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback =
        mock(BiFunction.class);
    doAnswer(
            inv -> {
              callbackInvoked[0] = true;
              capturedContext[0] = inv.getArgument(0);
              capturedSpan[0] = inv.getArgument(1);
              return new Flow.ResultFlow<>(null);
            })
        .when(requestEndedCallback)
        .apply(any(RequestContext.class), any());

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestEnded())).thenReturn(requestEndedCallback);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processRequestEnd(span);

    assertTrue(callbackInvoked[0]);
    assertEquals(mockRequestContext, capturedContext[0]);
    assertEquals(span, capturedSpan[0]);
  }

  @Test
  void processRequestEndHandlesNullRequestEndedCallbackGracefully() {
    RequestContext mockRequestContext = mock(RequestContext.class);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mockRequestContext);

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestEnded())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processRequestEnd(span);
    // no exception expected
  }

  // ============================================================================
  // mergeContexts Tests
  // ============================================================================

  @Test
  void mergeContextsReturnsNullWhenBothContextsAreNull() {
    assertNull(LambdaAppSecHandler.mergeContexts(null, null));
  }

  @Test
  void mergeContextsReturnsExtensionContextWhenAppSecContextIsNull() {
    TagContext extensionContext = mock(TagContext.class);
    assertEquals(extensionContext, LambdaAppSecHandler.mergeContexts(extensionContext, null));
  }

  @Test
  void mergeContextsReturnsAppSecContextWhenExtensionContextIsNull() {
    TagContext appSecContext = mock(TagContext.class);
    assertEquals(appSecContext, LambdaAppSecHandler.mergeContexts(null, appSecContext));
  }

  @Test
  void mergeContextsMergesAppSecDataIntoTagContext() {
    Object appSecData = new Object();
    TagContext appSecContext = new TagContext();
    appSecContext.withRequestContextDataAppSec(appSecData);
    TagContext extensionContext = new TagContext();

    AgentSpanContext result = LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext);

    assertEquals(extensionContext, result);
    assertEquals(appSecData, ((TagContext) result).getRequestContextDataAppSec());
  }

  @Test
  void mergeContextsReturnsExtensionContextWhenAppSecContextIsNotTagContext() {
    TagContext extensionContext = mock(TagContext.class);
    AgentSpanContext appSecContext = mock(AgentSpanContext.class);
    assertEquals(
        extensionContext, LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext));
  }

  @Test
  void mergeContextsReturnsExtensionContextWhenItIsNotTagContext() {
    AgentSpanContext extensionContext = mock(AgentSpanContext.class);
    TagContext appSecContext = mock(TagContext.class);
    assertEquals(
        extensionContext, LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext));
  }

  // ============================================================================
  // Error Handling and Null Callback Tests
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  void processRequestStartHandlesNullRequestStartedCallbackGracefully() {
    String eventJson = "{\"requestContext\": {\"httpMethod\": \"GET\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    assertNull(LambdaAppSecHandler.processRequestStart(event));
  }

  @Test
  @SuppressWarnings("unchecked")
  void processRequestStartHandlesNullMethodUriCallbackGracefully() {
    String eventJson = "{\"path\": \"/test\", \"requestContext\": {\"httpMethod\": \"GET\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object mockAppSecContext = new Object();
    Supplier<Flow<Object>> requestStartedCallback = mock(Supplier.class);
    when(requestStartedCallback.get()).thenReturn(new Flow.ResultFlow<>(mockAppSecContext));

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted()))
        .thenReturn(requestStartedCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestMethodUriRaw())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeader())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestClientSocketAddress())).thenReturn(null);
    Function<RequestContext, Flow<Void>> headerDoneCallback = mock(Function.class);
    when(headerDoneCallback.apply(any())).thenReturn(new Flow.ResultFlow<>(null));
    when(mockCallbackProvider.getCallback(EVENTS.requestHeaderDone()))
        .thenReturn(headerDoneCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestPathParams())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestBodyProcessed())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertInstanceOf(TagContext.class, result);
  }

  @Test
  void processRequestStartHandlesExceptionDuringJsonParsing() {
    ByteArrayInputStream event = createInputStream("{this is not valid JSON at all");
    assertNull(LambdaAppSecHandler.processRequestStart(event));
  }

  @Test
  void processRequestStartHandlesExceptionDuringStreamReading() {
    ByteArrayInputStream mockStream =
        new ByteArrayInputStream("data".getBytes()) {
          @Override
          public synchronized int available() {
            throw new RuntimeException("Stream error");
          }
        };
    assertNull(LambdaAppSecHandler.processRequestStart(mockStream));
  }

  // ============================================================================
  // TemporaryRequestContext Tests
  // ============================================================================

  @Test
  void temporaryRequestContextProvidesAppSecDataViaGetData() {
    Object mockAppSecContext = new Object();
    RequestContext ctx = captureTemporaryRequestContext(mockAppSecContext);

    assertNotNull(ctx);
    assertEquals(mockAppSecContext, ctx.getData(RequestContextSlot.APPSEC));
    assertNull(ctx.getData(RequestContextSlot.CI_VISIBILITY));
  }

  @Test
  void temporaryRequestContextNoOpMethodsReturnExpectedDefaults() throws Exception {
    RequestContext ctx = captureTemporaryRequestContext(new Object());

    assertNotNull(ctx);
    assertEquals(TraceSegment.NoOp.INSTANCE, ctx.getTraceSegment());
    assertNull(ctx.getBlockResponseFunction());
    assertNull(ctx.getOrCreateMetaStructTop("key", k -> new Object()));
    assertNull(ctx.getClientIpAddressData());
    // verify no-op methods don't throw
    ctx.setBlockResponseFunction(mock(BlockResponseFunction.class));
    ctx.setClientIpAddressData(mock(ClientIpAddressData.class));
    ctx.close();
  }

  private RequestContext captureTemporaryRequestContext(Object appSecContext) {
    String eventJson =
        "{\n"
            + "  \"path\": \"/test\",\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"GET\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    RequestContext[] captured = {null};

    Supplier<Flow<Object>> requestStartedCallback = mock(Supplier.class);
    when(requestStartedCallback.get()).thenReturn(new Flow.ResultFlow<>(appSecContext));

    TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> methodUriCallback =
        mock(TriFunction.class);
    doAnswer(
            inv -> {
              captured[0] = inv.getArgument(0);
              return new Flow.ResultFlow<>(null);
            })
        .when(methodUriCallback)
        .apply(any(), any(), any());

    Function<RequestContext, Flow<Void>> headerDoneCallback = mock(Function.class);
    when(headerDoneCallback.apply(any())).thenReturn(new Flow.ResultFlow<>(null));

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted()))
        .thenReturn(requestStartedCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestMethodUriRaw()))
        .thenReturn(methodUriCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeader())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestClientSocketAddress())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeaderDone()))
        .thenReturn(headerDoneCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestPathParams())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestBodyProcessed())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processRequestStart(event);
    return captured[0];
  }

  // ============================================================================
  // processResponseData Tests — guard conditions
  // ============================================================================

  @Test
  void processResponseDataDoesNothingWhenAppSecIsDisabled() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpan span = mock(AgentSpan.class);
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200, \"body\": \"ok\"}");
    LambdaAppSecHandler.processResponseData(span, result);
    verify(span, never()).getRequestContext();
  }

  @Test
  void processResponseDataDoesNothingForNullSpan() {
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200}");
    LambdaAppSecHandler.processResponseData(null, result);
    // no exception expected
  }

  @Test
  void processResponseDataDoesNothingForNonByteArrayOutputStreamResult() {
    AgentSpan span = mock(AgentSpan.class);
    LambdaAppSecHandler.processResponseData(span, "string result");
    verify(span, never()).getRequestContext();
  }

  @Test
  void processResponseDataDoesNothingForNullResult() {
    AgentSpan span = mock(AgentSpan.class);
    LambdaAppSecHandler.processResponseData(span, null);
    verify(span, never()).getRequestContext();
  }

  @Test
  void processResponseDataDoesNothingWhenSpanHasNoRequestContext() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(null);
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200}");
    setupMockResponseCallbacks(null, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    // no exception expected
  }

  @Test
  void processResponseDataDoesNothingForOversizedResponse() {
    int maxSize = Config.get().getAppSecBodyParsingSizeLimit();
    char[] chars = new char[maxSize + 1];
    java.util.Arrays.fill(chars, 'x');
    ByteArrayOutputStream result = createOutputStream(new String(chars));
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
  }

  @Test
  void processResponseDataDoesNothingForEmptyByteArrayOutputStream() {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
  }

  // --- Trigger type gating and fallback ---

  @Test
  void processResponseDataSkipsNonApiGwResponseWhenTriggerTypeIsUnknown() {
    LambdaAppSecHandler.setCurrentTriggerType(LambdaAppSecHandler.LambdaTriggerType.UNKNOWN);
    ByteArrayOutputStream result = createOutputStream("{\"result\": \"hello\"}");
    Integer[] capturedStatus = {null};
    boolean[] headerDoneCalled = {false};
    AgentSpan span =
        setupMockResponseCallbacks(
            status -> capturedStatus[0] = status, null, () -> headerDoneCalled[0] = true, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
    assertFalse(headerDoneCalled[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataAppliesFallbackForHttpTriggerWithPlainJsonResponse() {
    LambdaAppSecHandler.setCurrentTriggerType(LambdaAppSecHandler.LambdaTriggerType.LAMBDA_URL);
    ByteArrayOutputStream result = createOutputStream("{\"result\": \"hello\"}");
    Integer[] capturedStatus = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    boolean[] headerDoneCalled = {false};
    Object[] capturedBody = {null};
    AgentSpan span =
        setupMockResponseCallbacks(
            status -> capturedStatus[0] = status,
            capturedHeaders::put,
            () -> headerDoneCalled[0] = true,
            body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
    assertEquals("application/json", capturedHeaders.get("content-type"));
    assertTrue(headerDoneCalled[0]);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("hello", ((Map<?, ?>) capturedBody[0]).get("result"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataKeepsParsedHeadersAndBodyWhenStatusCodeIsZero() {
    // A response that has statusCode:0 with explicit headers/body should use the parsed data,
    // not discard it in favour of the plain-response fallback.
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    ByteArrayOutputStream result =
        createOutputStream(
            "{\"statusCode\": 0, \"headers\": {\"content-type\": \"text/plain\"}, \"body\": \"hello\"}");
    Integer[] capturedStatus = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    boolean[] headerDoneCalled = {false};
    Object[] capturedBody = {null};
    AgentSpan span =
        setupMockResponseCallbacks(
            status -> capturedStatus[0] = status,
            capturedHeaders::put,
            () -> headerDoneCalled[0] = true,
            body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]); // statusCode 0 — responseStarted not fired
    assertEquals("text/plain", capturedHeaders.get("content-type")); // parsed header kept
    assertTrue(headerDoneCalled[0]);
    assertEquals("hello", capturedBody[0]); // parsed body kept, not the whole envelope
  }

  @Test
  void processResponseDataAppliesFallbackForHttpTriggerWithNonJsonStringResponse() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    // JSON-encoded string (as returned by a RequestHandler<I, String>)
    ByteArrayOutputStream result = createOutputStream("\"Hello World!\"");
    Integer[] capturedStatus = {null};
    boolean[] headerDoneCalled = {false};
    Object[] capturedBody = {null};
    AgentSpan span =
        setupMockResponseCallbacks(
            status -> capturedStatus[0] = status,
            null,
            () -> headerDoneCalled[0] = true,
            body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
    assertTrue(headerDoneCalled[0]);
    assertEquals("Hello World!", capturedBody[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataWebSocketWithStatusCodeFiresResponseStarted() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET);
    // $connect handler returning a proper statusCode — should be treated like any API-GW response
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200}");
    Integer[] capturedStatus = {null};
    boolean[] headerDoneCalled = {false};
    AgentSpan span =
        setupMockResponseCallbacks(
            status -> capturedStatus[0] = status, null, () -> headerDoneCalled[0] = true, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals(200, capturedStatus[0]);
    assertTrue(headerDoneCalled[0]);
  }

  @Test
  void processResponseDataWebSocketWithoutStatusCodeUsesFallbackWithNoStatus() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET);
    // Message-route handler returning arbitrary data — no statusCode, fallback path
    ByteArrayOutputStream result = createOutputStream("{\"message\": \"hello\"}");
    Integer[] capturedStatus = {null};
    boolean[] headerDoneCalled = {false};
    Object[] capturedBody = {null};
    AgentSpan span =
        setupMockResponseCallbacks(
            status -> capturedStatus[0] = status,
            null,
            () -> headerDoneCalled[0] = true,
            body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]); // no responseStarted for status-less WebSocket messages
    assertTrue(headerDoneCalled[0]);
    assertInstanceOf(Map.class, capturedBody[0]);
  }

  @Test
  void processResponseDataSkipsNonApiGwResponseWhenTriggerTypeIsNull() {
    // No processRequestStart called — thread-local is null — behaves like unknown
    ByteArrayOutputStream result = createOutputStream("{\"result\": \"hello\"}");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
  }

  // --- Status code extraction ---

  @Test
  void processResponseDataExtractsStatusCodeCorrectly() {
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200, \"body\": \"ok\"}");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals(200, capturedStatus[0]);
  }

  @Test
  void processResponseDataExtractsStatusCodeAsIntegerFromDouble() {
    ByteArrayOutputStream result =
        createOutputStream("{\"statusCode\": 404.0, \"body\": \"not found\"}");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals(404, capturedStatus[0]);
  }

  @Test
  void processResponseDataHandlesMissingStatusCode() {
    ByteArrayOutputStream result = createOutputStream("{\"body\": \"ok\"}");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
  }

  @Test
  void processResponseDataHandlesNonNumericStatusCode() {
    ByteArrayOutputStream result =
        createOutputStream("{\"statusCode\": \"bad\", \"body\": \"ok\"}");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
  }

  // --- Header extraction ---

  @Test
  void processResponseDataForwardsAllResponseHeaders() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/json\", \"x-custom\": \"val\", \"content-length\": \"42\", \"set-cookie\": \"a=1\"}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals(4, capturedHeaders.size());
    assertEquals("application/json", capturedHeaders.get("content-type"));
    assertEquals("val", capturedHeaders.get("x-custom"));
    assertEquals("42", capturedHeaders.get("content-length"));
    assertEquals("a=1", capturedHeaders.get("set-cookie"));
  }

  @Test
  void processResponseDataLowercasesHeaderKeys() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"Content-Type\": \"text/html\", \"CONTENT-LENGTH\": \"10\"}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("text/html", capturedHeaders.get("content-type"));
    assertEquals("10", capturedHeaders.get("content-length"));
  }

  @Test
  void processResponseDataMergesMultiValueHeadersWithSingleValueHeaders() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/html\"}, \"multiValueHeaders\": {\"content-encoding\": [\"gzip\", \"br\"]}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("text/html", capturedHeaders.get("content-type"));
    assertEquals("gzip, br", capturedHeaders.get("content-encoding"));
  }

  @Test
  void processResponseDataHandlesEmptyHeaders() {
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200}");
    Map<String, String> capturedHeaders = new HashMap<>();
    boolean[] headerDoneCalled = {false};
    AgentSpan span =
        setupMockResponseCallbacks(
            null, capturedHeaders::put, () -> headerDoneCalled[0] = true, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertTrue(capturedHeaders.isEmpty());
    assertTrue(headerDoneCalled[0]);
  }

  // --- Body extraction ---

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataParsesJsonBody() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/json\"}, \"body\": \"{\\\"key\\\": \\\"value\\\"}\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("value", ((Map<?, ?>) capturedBody[0]).get("key"));
  }

  @Test
  void processResponseDataHandlesNonJsonBodyAsRawString() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/plain\"}, \"body\": \"plain text\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("plain text", capturedBody[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataHandlesBase64EncodedBody() {
    String originalBody = "{\"decoded\": \"content\"}";
    String base64Body =
        Base64.getEncoder().encodeToString(originalBody.getBytes(StandardCharsets.UTF_8));
    String json =
        "{\"statusCode\": 200, \"body\": \"" + base64Body + "\", \"isBase64Encoded\": true}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("content", ((Map<?, ?>) capturedBody[0]).get("decoded"));
  }

  @Test
  void processResponseDataHandlesNullBody() {
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200, \"body\": null}");
    String[] capturedBody = {"NOT_CALLED"};
    AgentSpan span =
        setupMockResponseCallbacks(
            null, null, null, body -> capturedBody[0] = String.valueOf(body));
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("NOT_CALLED", capturedBody[0]);
  }

  @Test
  void processResponseDataHandlesMissingBodyField() {
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200}");
    String[] capturedBody = {"NOT_CALLED"};
    AgentSpan span =
        setupMockResponseCallbacks(
            null, null, null, body -> capturedBody[0] = String.valueOf(body));
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("NOT_CALLED", capturedBody[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataAttemptsJsonParseWhenNoContentType() {
    ByteArrayOutputStream result =
        createOutputStream("{\"statusCode\": 200, \"body\": \"{\\\"a\\\": 1}\"}");
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals(1.0d, ((Map<?, ?>) capturedBody[0]).get("a"));
  }

  @Test
  void processResponseDataFallsBackToRawStringWhenJsonParseFails() {
    ByteArrayOutputStream result =
        createOutputStream("{\"statusCode\": 200, \"body\": \"not json {\"}");
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("not json {", capturedBody[0]);
  }

  // --- Event ordering ---

  @Test
  void processResponseDataFiresEventsInCorrectOrder() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/json\"}, \"body\": \"{\\\"k\\\": \\\"v\\\"}\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    java.util.List<String> order = new java.util.ArrayList<>();

    AgentSpan span =
        setupMockResponseCallbacks(
            status -> order.add("responseStarted"),
            (name, value) -> order.add("responseHeader"),
            () -> order.add("responseHeaderDone"),
            body -> order.add("responseBody"));

    LambdaAppSecHandler.processResponseData(span, result);

    assertEquals("responseStarted", order.get(0));
    assertTrue(order.stream().filter("responseHeader"::equals).count() >= 1);
    int headerDoneIdx = order.indexOf("responseHeaderDone");
    int lastHeaderIdx = order.lastIndexOf("responseHeader");
    assertTrue(headerDoneIdx > lastHeaderIdx);
    assertEquals("responseBody", order.get(order.size() - 1));
  }

  @Test
  void processResponseDataHandlesInvalidBase64ResponseBodyGracefully() {
    String json =
        "{\"statusCode\": 200, \"body\": \"not-valid-base64!!!\", \"isBase64Encoded\": true}";
    ByteArrayOutputStream result = createOutputStream(json);
    String[] capturedBody = {"NOT_CALLED"};
    AgentSpan span =
        setupMockResponseCallbacks(
            null, null, null, body -> capturedBody[0] = String.valueOf(body));
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("NOT_CALLED", capturedBody[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataParsesBodyAsJsonForJavascriptContentType() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/javascript\"}, \"body\": \"{\\\"key\\\": \\\"val\\\"}\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("val", ((Map<?, ?>) capturedBody[0]).get("key"));
  }

  @Test
  void processResponseDataSkipsMultiValueHeadersEntryWithNonListValue() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/html\"}, \"multiValueHeaders\": {\"x-scalar\": \"not-a-list\", \"x-valid\": [\"v1\", \"v2\"]}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("text/html", capturedHeaders.get("content-type"));
    assertEquals("v1, v2", capturedHeaders.get("x-valid"));
    assertFalse(capturedHeaders.containsKey("x-scalar"));
  }

  @Test
  void processResponseDataMultiValueHeadersOverrideSingleValueHeaders() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/html\"}, \"multiValueHeaders\": {\"content-type\": [\"application/json\", \"charset=utf-8\"]}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("application/json, charset=utf-8", capturedHeaders.get("content-type"));
  }

  // --- Error handling ---

  @Test
  void processResponseDataHandlesMalformedJsonResponse() {
    ByteArrayOutputStream result = createOutputStream("{not valid json");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertNull(capturedStatus[0]);
  }

  @Test
  void processResponseDataHandlesEmptyStringResponse() {
    ByteArrayOutputStream result = createOutputStream("");
    AgentSpan span = mock(AgentSpan.class);
    LambdaAppSecHandler.processResponseData(span, result);
    // no exception expected
  }

  // ============================================================================
  // processResponseData — null individual callback handling
  // ============================================================================

  @Test
  void processResponseDataHandlesNullResponseHeaderDoneCallbackGracefully() {
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/plain\"}, \"body\": \"ok\"}";
    ByteArrayOutputStream result = createOutputStream(json);

    RequestContext mockRequestContext = mock(RequestContext.class);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mockRequestContext);

    CallbackProvider cbp = mock(CallbackProvider.class);
    when(cbp.getCallback(EVENTS.responseStarted())).thenReturn(null);
    when(cbp.getCallback(EVENTS.responseHeader())).thenReturn(null);
    when(cbp.getCallback(EVENTS.responseHeaderDone())).thenReturn(null);
    when(cbp.getCallback(EVENTS.responseBody())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(cbp);
    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processResponseData(span, result);
    // no exception expected — all null callbacks must be silently skipped
  }

  // ============================================================================
  // extractResponseData Unit Tests
  // ============================================================================

  @Test
  void extractResponseDataReturnsNullForMalformedJson() {
    assertNull(LambdaAppSecHandler.extractResponseData("{bad json"));
  }

  @Test
  void extractResponseDataReturnsNullForNullJsonParseResult() {
    assertNull(LambdaAppSecHandler.extractResponseData("null"));
  }

  @Test
  void extractResponseDataReturnsNullForEmptyString() {
    assertNull(LambdaAppSecHandler.extractResponseData(""));
  }

  // ============================================================================
  // Helper Methods
  // ============================================================================

  private static ByteArrayInputStream createInputStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  private static ByteArrayOutputStream createOutputStream(String json) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      baos.write(json.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return baos;
  }

  private static class Callbacks {
    BiConsumer<String, URIDataAdapter> onMethodUri;
    BiConsumer<String, String> onHeader;
    BiConsumer<String, Integer> onSocketAddress;
    Consumer<Map<String, Object>> onPathParams;
    Consumer<Object> onBody;

    Callbacks onMethodUri(BiConsumer<String, URIDataAdapter> cb) {
      this.onMethodUri = cb;
      return this;
    }

    Callbacks onHeader(BiConsumer<String, String> cb) {
      this.onHeader = cb;
      return this;
    }

    Callbacks onSocketAddress(BiConsumer<String, Integer> cb) {
      this.onSocketAddress = cb;
      return this;
    }

    Callbacks onPathParams(Consumer<Map<String, Object>> cb) {
      this.onPathParams = cb;
      return this;
    }

    Callbacks onBody(Consumer<Object> cb) {
      this.onBody = cb;
      return this;
    }
  }

  @SuppressWarnings("unchecked")
  private void setupMockCallbacks(Callbacks callbacks) {
    Object mockAppSecContext = new Object();
    Supplier<Flow<Object>> requestStartedCallback = mock(Supplier.class);
    when(requestStartedCallback.get()).thenReturn(new Flow.ResultFlow<>(mockAppSecContext));

    TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> methodUriCallback = null;
    if (callbacks.onMethodUri != null) {
      methodUriCallback = mock(TriFunction.class);
      BiConsumer<String, URIDataAdapter> capture = callbacks.onMethodUri;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1), inv.getArgument(2));
                return Flow.ResultFlow.empty();
              })
          .when(methodUriCallback)
          .apply(any(), anyString(), any(URIDataAdapter.class));
    }

    TriConsumer<RequestContext, String, String> headerCallback = null;
    if (callbacks.onHeader != null) {
      headerCallback = mock(TriConsumer.class);
      BiConsumer<String, String> capture = callbacks.onHeader;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1), inv.getArgument(2));
                return null;
              })
          .when(headerCallback)
          .accept(any(), anyString(), anyString());
    }

    TriFunction<RequestContext, String, Integer, Flow<Void>> socketAddressCallback = null;
    if (callbacks.onSocketAddress != null) {
      socketAddressCallback = mock(TriFunction.class);
      BiConsumer<String, Integer> capture = callbacks.onSocketAddress;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1), (Integer) inv.getArgument(2));
                return Flow.ResultFlow.empty();
              })
          .when(socketAddressCallback)
          .apply(any(), anyString(), anyInt());
    }

    Function<RequestContext, Flow<Void>> headerDoneCallback = mock(Function.class);
    when(headerDoneCallback.apply(any())).thenReturn(Flow.ResultFlow.empty());

    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> pathParamsCallback = null;
    if (callbacks.onPathParams != null) {
      pathParamsCallback = mock(BiFunction.class);
      Consumer<Map<String, Object>> capture = callbacks.onPathParams;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1));
                return Flow.ResultFlow.empty();
              })
          .when(pathParamsCallback)
          .apply(any(), any(Map.class));
    }

    BiFunction<RequestContext, Object, Flow<Void>> bodyCallback = null;
    if (callbacks.onBody != null) {
      bodyCallback = mock(BiFunction.class);
      Consumer<Object> capture = callbacks.onBody;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1));
                return Flow.ResultFlow.empty();
              })
          .when(bodyCallback)
          .apply(any(), any());
    }

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted()))
        .thenReturn(requestStartedCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestMethodUriRaw()))
        .thenReturn(methodUriCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeader())).thenReturn(headerCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestClientSocketAddress()))
        .thenReturn(socketAddressCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeaderDone()))
        .thenReturn(headerDoneCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestPathParams()))
        .thenReturn(pathParamsCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestBodyProcessed())).thenReturn(bodyCallback);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);
  }

  private static Map<String, Object> mapOf(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  @SuppressWarnings("unchecked")
  private AgentSpan setupMockResponseCallbacks(
      Consumer<Integer> onResponseStarted,
      BiConsumer<String, String> onResponseHeader,
      Runnable onResponseHeaderDone,
      Consumer<Object> onResponseBody) {

    RequestContext mockRequestContext = mock(RequestContext.class);
    AgentSpan mockSpan = mock(AgentSpan.class);
    when(mockSpan.getRequestContext()).thenReturn(mockRequestContext);

    BiFunction<RequestContext, Integer, Flow<Void>> responseStartedCb = null;
    if (onResponseStarted != null) {
      responseStartedCb = mock(BiFunction.class);
      Consumer<Integer> capture = onResponseStarted;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1));
                return new Flow.ResultFlow<>(null);
              })
          .when(responseStartedCb)
          .apply(any(RequestContext.class), anyInt());
    }

    TriConsumer<RequestContext, String, String> responseHeaderCb = null;
    if (onResponseHeader != null) {
      responseHeaderCb = mock(TriConsumer.class);
      BiConsumer<String, String> capture = onResponseHeader;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1), inv.getArgument(2));
                return null;
              })
          .when(responseHeaderCb)
          .accept(any(), anyString(), anyString());
    }

    Function<RequestContext, Flow<Void>> responseHeaderDoneCb = mock(Function.class);
    if (onResponseHeaderDone != null) {
      Runnable capture = onResponseHeaderDone;
      doAnswer(
              inv -> {
                capture.run();
                return new Flow.ResultFlow<>(null);
              })
          .when(responseHeaderDoneCb)
          .apply(any(RequestContext.class));
    } else {
      when(responseHeaderDoneCb.apply(any())).thenReturn(new Flow.ResultFlow<>(null));
    }

    BiFunction<RequestContext, Object, Flow<Void>> responseBodyCb = null;
    if (onResponseBody != null) {
      responseBodyCb = mock(BiFunction.class);
      Consumer<Object> capture = onResponseBody;
      doAnswer(
              inv -> {
                capture.accept(inv.getArgument(1));
                return new Flow.ResultFlow<>(null);
              })
          .when(responseBodyCb)
          .apply(any(RequestContext.class), any());
    }

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.responseStarted())).thenReturn(responseStartedCb);
    when(mockCallbackProvider.getCallback(EVENTS.responseHeader())).thenReturn(responseHeaderCb);
    when(mockCallbackProvider.getCallback(EVENTS.responseHeaderDone()))
        .thenReturn(responseHeaderDoneCb);
    when(mockCallbackProvider.getCallback(EVENTS.responseBody())).thenReturn(responseBodyCb);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    return mockSpan;
  }
}
