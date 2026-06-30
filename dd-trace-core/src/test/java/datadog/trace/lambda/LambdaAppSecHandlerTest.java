package datadog.trace.lambda;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
public class LambdaAppSecHandlerTest extends DDCoreJavaSpecification {

  private static boolean originalAppSecActive;
  private static AgentTracer.TracerAPI originalTracer;

  @BeforeAll
  static void setupSpec() {
    originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    originalTracer = AgentTracer.get();
  }

  @BeforeEach
  void setup() {
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
    AgentTracer.forceRegister(originalTracer);
  }

  // ============================================================================
  // processRequestStart basic tests
  // ============================================================================

  @Test
  void processRequestStartReturnsNullWhenAppSecIsDisabled() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    ByteArrayInputStream event = createInputStream("{\"test\": \"data\"}");

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result);
  }

  @Test
  void processRequestStartReturnsNullForNonByteArrayInputStream() {
    AgentSpanContext result = LambdaAppSecHandler.processRequestStart("not a stream");

    assertNull(result);
  }

  @Test
  void processRequestStartReturnsNullForNullEvent() {
    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(null);

    assertNull(result);
  }

  @Test
  void processRequestStartReturnsNullForOversizedEvent() {
    int maxSize = Config.get().getAppSecBodyParsingSizeLimit();
    String largeBody = repeatChar('x', maxSize + 1);
    ByteArrayInputStream event = createInputStream(largeBody);

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result);
  }

  @Test
  void processRequestStartReturnsNullForZeroSizeEvent() {
    ByteArrayInputStream event = createInputStream("");

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result);
  }

  @Test
  void processRequestStartReturnsNullForMalformedJSON() {
    ByteArrayInputStream event = createInputStream("{invalid json");

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result);
  }

  @Test
  void streamCanBeReadMultipleTimesAfterProcessing() throws Exception {
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
  void extractsApiGatewayV1RestDataCorrectly() {
    String eventJson =
        "{\n"
            + "  \"path\": \"/api/users/123\",\n"
            + "  \"httpMethod\": \"POST\",\n"
            + "  \"headers\": {\n"
            + "    \"Content-Type\": \"application/json\",\n"
            + "    \"Authorization\": \"Bearer token123\"\n"
            + "  },\n"
            + "  \"pathParameters\": {\n"
            + "    \"userId\": \"123\"\n"
            + "  },\n"
            + "  \"body\": \"{\\\"name\\\": \\\"John\\\"}\",\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"POST\",\n"
            + "    \"requestId\": \"req-123\",\n"
            + "    \"identity\": {\n"
            + "      \"sourceIp\": \"192.168.1.100\"\n"
            + "    }\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    String[] capturedSourceIp = {null};
    Integer[] capturedSourcePort = {null};
    Map<String, Object>[] capturedPathParams = new Map[] {null};
    Object[] capturedBody = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onHeader((name, value) -> capturedHeaders.put(name, value))
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
    assertEquals(Integer.valueOf(0), capturedSourcePort[0]);
    assertEquals("123", ((Map<?, ?>) capturedPathParams[0]).get("userId"));
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("John", ((Map<?, ?>) capturedBody[0]).get("name"));
  }

  @Test
  void extractsApiGatewayV2HttpDataCorrectly() {
    String eventJson =
        "{\n"
            + "  \"version\": \"2.0\",\n"
            + "  \"headers\": {\n"
            + "    \"content-type\": \"application/json\",\n"
            + "    \"x-custom-header\": \"custom-value\"\n"
            + "  },\n"
            + "  \"cookies\": [\"session=abc123\", \"user=john\"],\n"
            + "  \"pathParameters\": {\n"
            + "    \"id\": \"456\"\n"
            + "  },\n"
            + "  \"body\": \"test body\",\n"
            + "  \"requestContext\": {\n"
            + "    \"http\": {\n"
            + "      \"method\": \"PUT\",\n"
            + "      \"path\": \"/api/items/456\",\n"
            + "      \"sourceIp\": \"10.0.0.50\",\n"
            + "      \"sourcePort\": 54321\n"
            + "    },\n"
            + "    \"domainName\": \"api.example.com\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedMethod = {null};
    String[] capturedPath = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    String[] capturedSourceIp = {null};
    Integer[] capturedSourcePort = {null};
    Map<String, Object>[] capturedPathParams = new Map[] {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedMethod[0] = method;
                  capturedPath[0] = uri.path();
                })
            .onHeader((name, value) -> capturedHeaders.put(name, value))
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
    assertEquals(Integer.valueOf(54321), capturedSourcePort[0]);
    assertEquals("456", ((Map<?, ?>) capturedPathParams[0]).get("id"));
  }

  @Test
  void extractsLambdaFunctionUrlDataCorrectly() {
    String eventJson =
        "{\n"
            + "  \"version\": \"2.0\",\n"
            + "  \"headers\": {\n"
            + "    \"host\": \"xyz.lambda-url.us-east-1.on.aws\"\n"
            + "  },\n"
            + "  \"requestContext\": {\n"
            + "    \"http\": {\n"
            + "      \"method\": \"GET\",\n"
            + "      \"path\": \"/function/path\",\n"
            + "      \"sourceIp\": \"1.2.3.4\"\n"
            + "    },\n"
            + "    \"domainName\": \"xyz.lambda-url.us-east-1.on.aws\"\n"
            + "  }\n"
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
        "{\n"
            + "  \"path\": \"/alb/test\",\n"
            + "  \"httpMethod\": \"DELETE\",\n"
            + "  \"headers\": {\n"
            + "    \"x-forwarded-for\": \"203.0.113.42\",\n"
            + "    \"user-agent\": \"curl/7.64.1\"\n"
            + "  },\n"
            + "  \"requestContext\": {\n"
            + "    \"elb\": {\n"
            + "      \"targetGroupArn\": \"arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/my-target-group/50dc6c495c0c9188\"\n"
            + "    }\n"
            + "  }\n"
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
        "{\n"
            + "  \"path\": \"/test\",\n"
            + "  \"httpMethod\": \"GET\",\n"
            + "  \"multiValueHeaders\": {\n"
            + "    \"accept\": [\"text/html\", \"application/json\"],\n"
            + "    \"x-custom\": [\"value1\", \"value2\"]\n"
            + "  },\n"
            + "  \"requestContext\": {\n"
            + "    \"elb\": {\n"
            + "      \"targetGroupArn\": \"arn:aws:...\"\n"
            + "    }\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader((name, value) -> capturedHeaders.put(name, value)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("text/html, application/json", capturedHeaders.get("accept"));
    assertEquals("value1, value2", capturedHeaders.get("x-custom"));
  }

  @Test
  void handlesMultiValueHeadersWithEmptyList() {
    String eventJson =
        "{\n"
            + "  \"path\": \"/test\",\n"
            + "  \"httpMethod\": \"GET\",\n"
            + "  \"multiValueHeaders\": {\n"
            + "    \"accept\": [],\n"
            + "    \"x-custom\": [\"value1\"]\n"
            + "  },\n"
            + "  \"requestContext\": {\n"
            + "    \"elb\": {\n"
            + "      \"targetGroupArn\": \"arn:aws:...\"\n"
            + "    }\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader((name, value) -> capturedHeaders.put(name, value)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("", capturedHeaders.get("accept")); // Empty list should result in empty string
    assertEquals("value1", capturedHeaders.get("x-custom"));
  }

  @Test
  void extractsWebSocketDataCorrectly() {
    String eventJson =
        "{\n"
            + "  \"requestContext\": {\n"
            + "    \"routeKey\": \"$connect\",\n"
            + "    \"connectionId\": \"conn-abc123\",\n"
            + "    \"identity\": {\n"
            + "      \"sourceIp\": \"192.168.0.100\"\n"
            + "    }\n"
            + "  }\n"
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
        "{\n"
            + "  \"body\": \""
            + base64Body
            + "\",\n"
            + "  \"isBase64Encoded\": true,\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"POST\"\n"
            + "  }\n"
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
    assertEquals("NOT_CALLED", capturedBody[0]); // Callback should not be invoked for null body
  }

  @Test
  void handlesEmptyBodyCorrectly() {
    ByteArrayInputStream event =
        createInputStream("{\"body\": \"\", \"requestContext\": {\"httpMethod\": \"POST\"}}");

    Object[] capturedBody = {null};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("", capturedBody[0]); // Empty body is passed as empty string to WAF
  }

  @Test
  void handlesPathWithQueryStringCorrectly() {
    String eventJson =
        "{\n"
            + "  \"path\": \"/api/users?id=123&filter=active\",\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"GET\"\n"
            + "  }\n"
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
        "{\n"
            + "  \"path\": \"/api/test\",\n"
            + "  \"headers\": {\n"
            + "    \"x-forwarded-proto\": \"http\",\n"
            + "    \"x-forwarded-port\": \"8080\"\n"
            + "  },\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"GET\",\n"
            + "    \"requestId\": \"req-123\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedScheme = {null};
    Integer[] capturedPort = {null};

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
    assertEquals(Integer.valueOf(8080), capturedPort[0]);
  }

  @Test
  void fallsBackToHttps443WhenXForwardedHeadersAreAbsent() {
    String eventJson =
        "{\n"
            + "  \"path\": \"/api/test\",\n"
            + "  \"headers\": {},\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"GET\",\n"
            + "    \"requestId\": \"req-123\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedScheme = {null};
    Integer[] capturedPort = {null};

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
    assertEquals(Integer.valueOf(443), capturedPort[0]);
  }

  @Test
  void handlesInvalidXForwardedPortGracefully() {
    String eventJson =
        "{\n"
            + "  \"path\": \"/api/test\",\n"
            + "  \"headers\": {\n"
            + "    \"x-forwarded-proto\": \"https\",\n"
            + "    \"x-forwarded-port\": \"not-a-number\"\n"
            + "  },\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"GET\",\n"
            + "    \"requestId\": \"req-123\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedScheme = {null};
    Integer[] capturedPort = {null};

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
    assertEquals(Integer.valueOf(443), capturedPort[0]);
  }

  @Test
  void handlesInvalidBase64BodyGracefully() {
    String eventJson =
        "{\n"
            + "  \"body\": \"not-valid-base64\",\n"
            + "  \"isBase64Encoded\": true,\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"POST\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedBody = {"NOT_CALLED"};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = String.valueOf(body)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("NOT_CALLED", capturedBody[0]); // Should not call body callback when decode fails
  }

  @Test
  void handlesBase64DecodedEmptyStringBody() {
    String base64Empty = Base64.getEncoder().encodeToString("".getBytes());
    String eventJson =
        "{\n"
            + "  \"body\": \""
            + base64Empty
            + "\",\n"
            + "  \"isBase64Encoded\": true,\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"POST\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object[] capturedBody = {"NOT_CALLED"};

    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("", capturedBody[0]); // Should pass empty string after decoding
  }

  @Test
  void handlesBodyWithSpecialCharacters() {
    String eventJson =
        "{\n"
            + "  \"body\": \"{\\\"text\\\": \\\"Hello \\u4e16\\u754c \\uD83C\\uDF0D\\\"}\",\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"POST\"\n"
            + "  }\n"
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
        "{\n"
            + "  \"path\": \"/generic/path\",\n"
            + "  \"httpMethod\": \"PATCH\",\n"
            + "  \"headers\": {\n"
            + "    \"x-custom-header\": \"generic-value\"\n"
            + "  },\n"
            + "  \"unknownField\": \"should be ignored\",\n"
            + "  \"requestContext\": {\n"
            + "    \"identity\": {\n"
            + "      \"sourceIp\": \"203.0.113.1\"\n"
            + "    }\n"
            + "  }\n"
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
            .onHeader((name, value) -> capturedHeaders.put(name, value))
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
        "{\n"
            + "  \"requestContext\": {\n"
            + "    \"http\": {\n"
            + "      \"method\": \"OPTIONS\",\n"
            + "      \"path\": \"/options/path\",\n"
            + "      \"sourceIp\": \"198.51.100.50\"\n"
            + "    }\n"
            + "  }\n"
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
        "{\n"
            + "  \"headers\": {\n"
            + "    \"cookie\": \"existing=value\"\n"
            + "  },\n"
            + "  \"cookies\": [\"new=cookie1\", \"another=cookie2\"],\n"
            + "  \"requestContext\": {\n"
            + "    \"http\": {\n"
            + "      \"method\": \"GET\",\n"
            + "      \"path\": \"/\"\n"
            + "    }\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader((name, value) -> capturedHeaders.put(name, value)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("existing=value; new=cookie1; another=cookie2", capturedHeaders.get("cookie"));
  }

  @Test
  void handlesEmptyCookiesArrayCorrectly() {
    String eventJson =
        "{\n"
            + "  \"headers\": {\n"
            + "    \"content-type\": \"application/json\"\n"
            + "  },\n"
            + "  \"cookies\": [],\n"
            + "  \"requestContext\": {\n"
            + "    \"http\": {\n"
            + "      \"method\": \"GET\",\n"
            + "      \"path\": \"/\"\n"
            + "    }\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader((name, value) -> capturedHeaders.put(name, value)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertFalse(capturedHeaders.containsKey("cookie")); // Empty array should not add cookie header
  }

  // ============================================================================
  // processRequestEnd Tests
  // ============================================================================

  @Test
  void processRequestEndDoesNothingWhenSpanIsNull() {
    // No exception should be thrown
    LambdaAppSecHandler.processRequestEnd(null);
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

    // No exception should be thrown
    LambdaAppSecHandler.processRequestEnd(span);
  }

  @Test
  void processRequestEndInvokesRequestEndedCallbackWithRequestContext() {
    Object mockAppSecContext = new Object();
    RequestContext mockRequestContext = mock(RequestContext.class);
    when(mockRequestContext.getData(RequestContextSlot.APPSEC)).thenReturn(mockAppSecContext);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mockRequestContext);

    boolean[] callbackInvoked = {false};
    RequestContext[] capturedContext = {null};
    AgentSpan[] capturedSpan = {null};

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> mockRequestEndedCallback =
        mock(BiFunction.class);
    doAnswer(
            inv -> {
              capturedContext[0] = inv.getArgument(0);
              capturedSpan[0] = inv.getArgument(1);
              callbackInvoked[0] = true;
              return new Flow.ResultFlow<>(null);
            })
        .when(mockRequestEndedCallback)
        .apply(any(), any());

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestEnded()))
        .thenReturn(mockRequestEndedCallback);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);

    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processRequestEnd(span);

    assertEquals(true, callbackInvoked[0]);
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

    // No exception should be thrown - should log warning but not throw
    LambdaAppSecHandler.processRequestEnd(span);
  }

  // ============================================================================
  // mergeContexts Tests
  // ============================================================================

  @Test
  void mergeContextsReturnsNullWhenBothContextsAreNull() {
    AgentSpanContext result = LambdaAppSecHandler.mergeContexts(null, null);

    assertNull(result);
  }

  @Test
  void mergeContextsReturnsExtensionContextWhenAppSecContextIsNull() {
    TagContext extensionContext = mock(TagContext.class);

    AgentSpanContext result = LambdaAppSecHandler.mergeContexts(extensionContext, null);

    assertEquals(extensionContext, result);
  }

  @Test
  void mergeContextsReturnsAppSecContextWhenExtensionContextIsNull() {
    TagContext appSecContext = mock(TagContext.class);

    AgentSpanContext result = LambdaAppSecHandler.mergeContexts(null, appSecContext);

    assertEquals(appSecContext, result);
  }

  @Test
  void mergeContextsMergesAppSecDataIntoTagContext() {
    Object appSecData = new Object();

    // Create real TagContext instances since methods are final
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

    AgentSpanContext result = LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext);

    assertEquals(extensionContext, result);
  }

  @Test
  void mergeContextsReturnsExtensionContextWhenItIsNotTagContext() {
    AgentSpanContext extensionContext = mock(AgentSpanContext.class);
    TagContext appSecContext = mock(TagContext.class);

    AgentSpanContext result = LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext);

    assertEquals(extensionContext, result);
  }

  // ============================================================================
  // Error Handling and Null Callback Tests
  // ============================================================================

  @Test
  void processRequestStartHandlesNullRequestStartedCallbackGracefully() {
    String eventJson = "{\"requestContext\": {\"httpMethod\": \"GET\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);

    AgentTracer.forceRegister(mockTracer);

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result); // Should return null when requestStarted callback is missing
  }

  @Test
  void processRequestStartHandlesNullMethodUriCallbackGracefully() {
    String eventJson =
        "{\n"
            + "  \"path\": \"/test\",\n"
            + "  \"requestContext\": {\n"
            + "    \"httpMethod\": \"GET\"\n"
            + "  }\n"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object mockAppSecContext = new Object();

    Supplier<Flow<Object>> mockRequestStartedCallback = mock(Supplier.class);
    when(mockRequestStartedCallback.get()).thenReturn(new Flow.ResultFlow<>(mockAppSecContext));

    Function<RequestContext, Flow<Void>> mockHeaderDoneCallback = mock(Function.class);
    when(mockHeaderDoneCallback.apply(any())).thenReturn(new Flow.ResultFlow<>(null));

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted()))
        .thenReturn(mockRequestStartedCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestMethodUriRaw())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeader())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestClientSocketAddress())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeaderDone()))
        .thenReturn(mockHeaderDoneCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestPathParams())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestBodyProcessed())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);

    AgentTracer.forceRegister(mockTracer);

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result); // Should continue processing even if methodUri callback is null
    assertInstanceOf(TagContext.class, result);
  }

  @Test
  void processRequestStartHandlesExceptionDuringJsonParsing() {
    String invalidJson = "{this is not valid JSON at all";
    ByteArrayInputStream event = createInputStream(invalidJson);

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result); // Should return null on parse error
  }

  @Test
  void processRequestStartHandlesExceptionDuringStreamReading() throws IOException {
    ByteArrayInputStream mockStream = mock(ByteArrayInputStream.class);
    when(mockStream.available()).thenThrow(new IOException("Stream error"));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(mockStream);

    assertNull(result); // Should return null on IO error
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
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted())).thenReturn(requestStartedCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestMethodUriRaw())).thenReturn(methodUriCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeader())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestClientSocketAddress())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeaderDone())).thenReturn(headerDoneCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestPathParams())).thenReturn(null);
    when(mockCallbackProvider.getCallback(EVENTS.requestBodyProcessed())).thenReturn(null);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processRequestStart(event);
    return captured[0];
  }

  // ============================================================================
  // Helper classes and methods
  // ============================================================================

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

  private static Map<String, Object> mapOf(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  private ByteArrayInputStream createInputStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  private void setupMockCallbacks(Callbacks callbacks) {
    Object mockAppSecContext = new Object();

    Supplier<Flow<Object>> mockRequestStartedCallback = mock(Supplier.class);
    when(mockRequestStartedCallback.get()).thenReturn(new Flow.ResultFlow<>(mockAppSecContext));

    TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> mockMethodUriCallback = null;
    if (callbacks.onMethodUri != null) {
      mockMethodUriCallback = mock(TriFunction.class);
      BiConsumer<String, URIDataAdapter> methodUriCb = callbacks.onMethodUri;
      doAnswer(
              inv -> {
                String method = inv.getArgument(1);
                URIDataAdapter uri = inv.getArgument(2);
                methodUriCb.accept(method, uri);
                return new Flow.ResultFlow<>(null);
              })
          .when(mockMethodUriCallback)
          .apply(any(), any(), any());
    }

    TriConsumer<RequestContext, String, String> mockHeaderCallback = null;
    if (callbacks.onHeader != null) {
      mockHeaderCallback = mock(TriConsumer.class);
      BiConsumer<String, String> headerCb = callbacks.onHeader;
      doAnswer(
              inv -> {
                String name = inv.getArgument(1);
                String value = inv.getArgument(2);
                headerCb.accept(name, value);
                return null;
              })
          .when(mockHeaderCallback)
          .accept(any(), any(), any());
    }

    TriFunction<RequestContext, String, Integer, Flow<Void>> mockSocketAddressCallback = null;
    if (callbacks.onSocketAddress != null) {
      mockSocketAddressCallback = mock(TriFunction.class);
      BiConsumer<String, Integer> socketCb = callbacks.onSocketAddress;
      doAnswer(
              inv -> {
                String ip = inv.getArgument(1);
                Integer port = inv.getArgument(2);
                socketCb.accept(ip, port);
                return new Flow.ResultFlow<>(null);
              })
          .when(mockSocketAddressCallback)
          .apply(any(), any(), any());
    }

    Function<RequestContext, Flow<Void>> mockHeaderDoneCallback = mock(Function.class);
    when(mockHeaderDoneCallback.apply(any())).thenReturn(new Flow.ResultFlow<>(null));

    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> mockPathParamsCallback = null;
    if (callbacks.onPathParams != null) {
      mockPathParamsCallback = mock(BiFunction.class);
      Consumer<Map<String, Object>> pathParamsCb = callbacks.onPathParams;
      doAnswer(
              inv -> {
                Map<String, Object> params = inv.getArgument(1);
                pathParamsCb.accept(params);
                return new Flow.ResultFlow<>(null);
              })
          .when(mockPathParamsCallback)
          .apply(any(), any());
    }

    BiFunction<RequestContext, Object, Flow<Void>> mockBodyCallback = null;
    if (callbacks.onBody != null) {
      mockBodyCallback = mock(BiFunction.class);
      Consumer<Object> bodyCb = callbacks.onBody;
      doAnswer(
              inv -> {
                Object body = inv.getArgument(1);
                bodyCb.accept(body);
                return new Flow.ResultFlow<>(null);
              })
          .when(mockBodyCallback)
          .apply(any(), any());
    }

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted()))
        .thenReturn(mockRequestStartedCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestMethodUriRaw()))
        .thenReturn(mockMethodUriCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeader())).thenReturn(mockHeaderCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestClientSocketAddress()))
        .thenReturn(mockSocketAddressCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestHeaderDone()))
        .thenReturn(mockHeaderDoneCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestPathParams()))
        .thenReturn(mockPathParamsCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestBodyProcessed()))
        .thenReturn(mockBodyCallback);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);

    AgentTracer.forceRegister(mockTracer);
  }

  private static String repeatChar(char ch, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, ch);
    return new String(chars);
  }
}
