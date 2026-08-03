package datadog.trace.lambda;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.appsec.AppSecContext;
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
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.core.DDCoreJavaSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("triggerTypeDetectionCases")
  void detectsTriggerType(
      String description,
      Map<String, Object> event,
      LambdaAppSecHandler.LambdaTriggerType expected) {
    assertEquals(expected, LambdaAppSecHandler.detectTriggerType(event));
  }

  static Stream<Arguments> triggerTypeDetectionCases() {
    return Stream.of(
        Arguments.of(
            "API Gateway v1 REST",
            mapOf("requestContext", mapOf("httpMethod", "GET", "requestId", "abc123")),
            LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST),
        Arguments.of(
            "API Gateway v2 HTTP",
            mapOf(
                "requestContext",
                mapOf(
                    "http",
                    mapOf("method", "POST", "path", "/api"),
                    "domainName",
                    "api.example.com")),
            LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_HTTP),
        Arguments.of(
            "Lambda Function URL",
            mapOf(
                "requestContext",
                mapOf(
                    "http",
                    mapOf("method", "GET", "path", "/"),
                    "domainName",
                    "xyz123.lambda-url.us-east-1.on.aws")),
            LambdaAppSecHandler.LambdaTriggerType.LAMBDA_URL),
        Arguments.of(
            "ALB without multi-value headers",
            mapOf(
                "httpMethod",
                "GET",
                "path",
                "/",
                "requestContext",
                mapOf("elb", mapOf("targetGroupArn", "arn:aws:..."))),
            LambdaAppSecHandler.LambdaTriggerType.ALB),
        Arguments.of(
            "ALB with multi-value headers",
            mapOf(
                "httpMethod",
                "GET",
                "path",
                "/",
                "multiValueHeaders",
                mapOf("accept", Arrays.asList("text/html", "application/json")),
                "requestContext",
                mapOf("elb", mapOf("targetGroupArn", "arn:aws:..."))),
            LambdaAppSecHandler.LambdaTriggerType.ALB_MULTI_VALUE),
        Arguments.of(
            "WebSocket via routeKey",
            mapOf("requestContext", mapOf("connectionId", "conn-123", "routeKey", "$connect")),
            LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET),
        Arguments.of(
            "WebSocket via eventType",
            mapOf("requestContext", mapOf("connectionId", "conn-456", "eventType", "CONNECT")),
            LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET),
        Arguments.of(
            "Unknown for unrecognized event",
            mapOf("someUnknownField", "value"),
            LambdaAppSecHandler.LambdaTriggerType.UNKNOWN),
        Arguments.of(
            "Unknown for empty requestContext",
            mapOf("requestContext", mapOf()),
            LambdaAppSecHandler.LambdaTriggerType.UNKNOWN),
        // No domainName => no positive evidence of a Lambda Function URL (matching the Rust
        // extension and Python's datadog-lambda layer), so this falls through to
        // API_GATEWAY_V2_HTTP rather than defaulting to LAMBDA_URL.
        Arguments.of(
            "v2 HTTP when http present but no domainName",
            mapOf("requestContext", mapOf("http", mapOf("method", "GET", "path", "/ambiguous"))),
            LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_HTTP),
        // A non-string domainName is likewise not positive evidence of a Function URL.
        Arguments.of(
            "v2 HTTP when domainName is not a string",
            mapOf(
                "requestContext",
                mapOf("http", mapOf("method", "GET", "path", "/ambiguous"), "domainName", 12345)),
            LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_HTTP));
  }

  // ============================================================================
  // Data Extraction Tests with Mocked Callbacks
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  void extractsApiGatewayV1RestDataCorrectly() {
    String eventJson =
        "{\"path\": \"/api/users/123\",\"httpMethod\": \"POST\",\"headers\": {\"Content-Type\":"
            + " \"application/json\", \"Authorization\": \"Bearer token123\"},\"pathParameters\":"
            + " {\"userId\": \"123\"},\"body\": \"{\\\"name\\\":"
            + " \\\"John\\\"}\",\"requestContext\": {  \"httpMethod\": \"POST\",  \"requestId\":"
            + " \"req-123\",  \"identity\": {\"sourceIp\": \"192.168.1.100\"}}}";
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
    assertEquals("application/json", capturedHeaders.get("content-type"));
    assertEquals("Bearer token123", capturedHeaders.get("authorization"));
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
        "{\"version\": \"2.0\",\"headers\": {\"content-type\": \"application/json\","
            + " \"x-custom-header\": \"custom-value\"},\"cookies\": [\"session=abc123\","
            + " \"user=john\"],\"pathParameters\": {\"id\": \"456\"},\"body\": \"test"
            + " body\",\"requestContext\": {  \"http\": {\"method\": \"PUT\", \"path\":"
            + " \"/api/items/456\", \"sourceIp\": \"10.0.0.50\", \"sourcePort\": 54321}, "
            + " \"domainName\": \"api.example.com\"}}";
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
        "{\"version\": \"2.0\",\"headers\": {\"host\":"
            + " \"xyz.lambda-url.us-east-1.on.aws\"},\"requestContext\": {  \"http\": {\"method\":"
            + " \"GET\", \"path\": \"/function/path\", \"sourceIp\": \"1.2.3.4\"},  \"domainName\":"
            + " \"xyz.lambda-url.us-east-1.on.aws\"}}";
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
        "{\"path\": \"/alb/test\",\"httpMethod\": \"DELETE\",\"headers\": {\"x-forwarded-for\":"
            + " \"203.0.113.42\", \"user-agent\": \"curl/7.64.1\"},\"requestContext\": {  \"elb\":"
            + " {\"targetGroupArn\":"
            + " \"arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/tg/50dc6c495c0c9188\"}"
            + "}}";
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
        "{\"path\": \"/test\",\"httpMethod\": \"GET\",\"multiValueHeaders\": {\"accept\":"
            + " [\"text/html\", \"application/json\"], \"x-custom\": [\"value1\","
            + " \"value2\"]},\"requestContext\": {\"elb\": {\"targetGroupArn\": \"arn:aws:...\"}}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader(capturedHeaders::put));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("text/html, application/json", capturedHeaders.get("accept"));
    assertEquals("value1, value2", capturedHeaders.get("x-custom"));
  }

  @Test
  void albMultiValueQueryParamsHandlesListValues() {
    String eventJson =
        "{\"path\": \"/test\",\"httpMethod\": \"GET\",\"multiValueHeaders\": {\"accept\":"
            + " [\"text/html\"]},\"multiValueQueryStringParameters\": {\"foo\": [\"bar\", \"baz\"],"
            + " \"x\": [null, \"val\"]},\"requestContext\": {\"elb\": {\"targetGroupArn\":"
            + " \"arn:aws:...\"}}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedQuery = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedQuery[0] = uri.query();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertNotNull(capturedQuery[0]);
    assertTrue(capturedQuery[0].contains("foo=bar") || capturedQuery[0].contains("foo=baz"));
    assertTrue(capturedQuery[0].contains("x=val"));
  }

  @Test
  void albMultiValueHeadersHandlesNonListValue() {
    String eventJson =
        "{\"path\": \"/test\",\"httpMethod\": \"GET\",\"multiValueHeaders\": {\"content-type\":"
            + " \"text/plain\", \"accept\": [\"application/json\"]},\"requestContext\": {\"elb\":"
            + " {\"targetGroupArn\": \"arn:aws:...\"}}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Map<String, String> capturedHeaders = new HashMap<>();

    setupMockCallbacks(new Callbacks().onHeader(capturedHeaders::put));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("text/plain", capturedHeaders.get("content-type"));
    assertEquals("application/json", capturedHeaders.get("accept"));
  }

  @Test
  void nullJsonEventReturnsEmpty() {
    // MAP_ADAPTER.fromJson("null") returns null, triggering LambdaEventData.EMPTY path
    ByteArrayInputStream event = createInputStream("null");

    setupMockCallbacks(new Callbacks());

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result);
  }

  @Test
  void albMultiValueQueryParamsHandlesNonListValue() {
    String eventJson =
        "{"
            + "\"path\": \"/test\","
            + "\"httpMethod\": \"GET\","
            + "\"multiValueHeaders\": {\"accept\": [\"text/html\"]},"
            + "\"multiValueQueryStringParameters\": {\"foo\": \"plain-string\"},"
            + "\"requestContext\": {\"elb\": {\"targetGroupArn\": \"arn:aws:...\"}}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedQuery = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedQuery[0] = uri.query();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertNotNull(capturedQuery[0]);
    assertTrue(capturedQuery[0].contains("foo=plain-string"));
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
  void extractsQueryStringParametersAndBuildsFullUri() {
    String eventJson =
        "{"
            + "\"path\": \"/api/items\","
            + "\"queryStringParameters\": {\"page\": \"2\", \"sort\": \"asc\"},"
            + "\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-456\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedPath = {null};
    String[] capturedQuery = {null};
    String[] capturedRawPath = {null};
    String[] capturedRawQuery = {null};
    String[] capturedHost = {"NOT_CALLED"};
    String[] capturedFragment = {"NOT_CALLED"};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedPath[0] = uri.path();
                  capturedQuery[0] = uri.query();
                  capturedRawPath[0] = uri.rawPath();
                  capturedRawQuery[0] = uri.rawQuery();
                  capturedHost[0] = uri.host();
                  capturedFragment[0] = uri.fragment();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("/api/items", capturedPath[0]);
    assertNotNull(capturedQuery[0]);
    assertTrue(capturedQuery[0].contains("page=2"));
    assertTrue(capturedQuery[0].contains("sort=asc"));
    assertEquals("/api/items", capturedRawPath[0]);
    assertEquals(capturedQuery[0], capturedRawQuery[0]);
    assertNull(capturedHost[0]);
    assertNull(capturedFragment[0]);
  }

  @Test
  void extractQueryParametersFiltersNullEntries() {
    String eventJson =
        "{"
            + "\"path\": \"/filter-test\","
            + "\"queryStringParameters\": {\"valid\": \"keep\", \"nullval\": null},"
            + "\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-789\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedQuery = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedQuery[0] = uri.query();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertNotNull(capturedQuery[0]);
    assertTrue(capturedQuery[0].contains("valid=keep"));
    assertFalse(capturedQuery[0].contains("nullval"));
  }

  @Test
  void buildFullPathEncodesSpecialCharactersInQueryParams() {
    // Keys/values containing '&', '=', and spaces must be percent-encoded so they are not
    // misinterpreted as query string delimiters when AppSec parses the raw query string.
    String eventJson =
        "{\"path\": \"/search\",\"queryStringParameters\": {\"q\": \"hello world\", \"filter\":"
            + " \"a&b\", \"eq\": \"x=y\"},\"requestContext\": {\"httpMethod\": \"GET\","
            + " \"requestId\": \"req-special\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedQuery = {null};

    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedQuery[0] = uri.query();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertNotNull(capturedQuery[0]);
    // spaces encoded as '+' or '%20', '&' as '%26', '=' as '%3D'
    assertFalse(capturedQuery[0].contains("hello world"), "space must be encoded");
    assertFalse(capturedQuery[0].contains("a&b"), "'&' in value must be encoded");
    assertFalse(capturedQuery[0].contains("x=y"), "'=' in value must be encoded");
    assertTrue(
        capturedQuery[0].contains("hello+world") || capturedQuery[0].contains("hello%20world"),
        "space should be encoded as '+' or '%20'");
    assertTrue(capturedQuery[0].contains("a%26b"), "'&' should be encoded as '%26'");
    assertTrue(capturedQuery[0].contains("x%3Dy"), "'=' should be encoded as '%3D'");
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
        "{\"path\": \"/api/test\",\"headers\": {\"x-forwarded-proto\": \"https\","
            + " \"x-forwarded-port\": \"not-a-number\"},\"requestContext\": {\"httpMethod\":"
            + " \"GET\", \"requestId\": \"req-123\"}}";
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
  void extractsFirstSchemeAndPortFromCommaSeparatedXForwardedHeaders() {
    // Behind a chain of proxies these headers carry a comma-separated list; the first (client-most)
    // hop must be used. Previously x-forwarded-port took the raw value, so a comma-separated value
    // threw in Integer.parseInt and silently fell back to 443.
    String eventJson =
        "{"
            + "\"path\": \"/api/test\","
            + "\"headers\": {\"x-forwarded-proto\": \"http, https\","
            + " \"x-forwarded-port\": \"8080, 443\"},"
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
  // Body Content-Type Parsing Tests (URL-encoded / multipart)
  // ============================================================================

  @Test
  void parsesUrlEncodedBodyIntoMultiValueMap() {
    String eventJson =
        "{"
            + "\"body\": \"a=1&a=2&b=hello+world&c\","
            + "\"headers\": {\"Content-Type\": \"application/x-www-form-urlencoded\"},"
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object[] capturedBody = {null};
    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertInstanceOf(Map.class, capturedBody[0]);
    @SuppressWarnings("unchecked")
    Map<String, List<String>> body = (Map<String, List<String>>) capturedBody[0];
    assertEquals(Arrays.asList("1", "2"), body.get("a"));
    assertEquals(Collections.singletonList("hello world"), body.get("b"));
    assertEquals(Collections.singletonList(""), body.get("c"));
  }

  @Test
  void multipartBodyIsKeptAsRawString() {
    // multipart/form-data is not structurally parsed; the raw payload is forwarded so string-based
    // WAF rules can still scan it. (Structured multipart schema extraction is not a milestone-1
    // system-test gap for Java Lambda.)
    String multipartBody =
        "--BOUNDARY\r\n"
            + "Content-Disposition: form-data; name=\"field1\"\r\n"
            + "\r\n"
            + "value1\r\n"
            + "--BOUNDARY--\r\n";
    String eventJson =
        "{"
            + "\"body\": \""
            + escapeJson(multipartBody)
            + "\","
            + "\"headers\": {\"Content-Type\": \"multipart/form-data; boundary=BOUNDARY\"},"
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object[] capturedBody = {null};
    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals(multipartBody, capturedBody[0]);
  }

  @Test
  void keepsTextPlainRequestBodyWithJsonContentAsRawString() {
    // A text/plain request body that happens to be valid JSON must NOT be JSON-parsed; it is kept
    // as the raw string so it stays scannable (matches the extension; more protective than
    // datadog-lambda-python which drops it).
    String eventJson =
        "{"
            + "\"body\": \"{\\\"user\\\": \\\"admin\\\"}\","
            + "\"headers\": {\"Content-Type\": \"text/plain\"},"
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object[] capturedBody = {null};
    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = body));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("{\"user\": \"admin\"}", capturedBody[0]);
  }

  @Test
  void dropsMalformedRequestBodyUnderJsonContentType() {
    // Explicit application/json content-type with an invalid JSON body: dropped, so
    // requestBodyProcessed does not fire (matches the extension).
    String eventJson =
        "{"
            + "\"body\": \"not json {\","
            + "\"headers\": {\"Content-Type\": \"application/json\"},"
            + "\"requestContext\": {\"httpMethod\": \"POST\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedBody = {"NOT_CALLED"};
    setupMockCallbacks(new Callbacks().onBody(body -> capturedBody[0] = String.valueOf(body)));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("NOT_CALLED", capturedBody[0]);
  }

  private static String escapeJson(String raw) {
    return raw.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
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
        "{\"requestContext\": {  \"http\": {\"method\": \"OPTIONS\", \"path\": \"/options/path\","
            + " \"sourceIp\": \"198.51.100.50\"}}}";
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
  void genericExtractionUsesHttpMethodFromRequestContext() {
    String eventJson =
        "{\"path\": \"/ctx-method\", \"requestContext\": {\"httpMethod\": \"DELETE\"}}";
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
    assertEquals("DELETE", capturedMethod[0]);
    assertEquals("/ctx-method", capturedPath[0]);
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
  void processRequestEndInvokesCallbackAndSkipsAsmTagsWhenNotManuallyKept() {
    AppSecContext mockAppSecContext = mock(AppSecContext.class);
    when(mockAppSecContext.isManuallyKept()).thenReturn(false);
    TraceSegment mockTraceSegment = mock(TraceSegment.class);
    RequestContext mockRequestContext = mock(RequestContext.class);
    when(mockRequestContext.getData(RequestContextSlot.APPSEC)).thenReturn(mockAppSecContext);
    when(mockRequestContext.getTraceSegment()).thenReturn(mockTraceSegment);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mockRequestContext);

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback =
        mock(BiFunction.class);
    when(requestEndedCallback.apply(any(), any())).thenReturn(new Flow.ResultFlow<>(null));

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestEnded())).thenReturn(requestEndedCallback);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processRequestEnd(span);

    verify(requestEndedCallback).apply(mockRequestContext, span);
    verify(mockTraceSegment, never()).setTagTop(any(), any());
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

    assertDoesNotThrow(() -> LambdaAppSecHandler.processRequestEnd(span));
  }

  @Test
  @SuppressWarnings("unchecked")
  void processRequestEndSetsAsmKeepTagWhenAppSecContextIsManuallyKept() {
    AppSecContext manuallyKeptCtx = mock(AppSecContext.class);
    when(manuallyKeptCtx.isManuallyKept()).thenReturn(true);

    TraceSegment mockTraceSegment = mock(TraceSegment.class);
    RequestContext mockRequestContext = mock(RequestContext.class);
    when(mockRequestContext.getData(RequestContextSlot.APPSEC)).thenReturn(manuallyKeptCtx);
    when(mockRequestContext.getTraceSegment()).thenReturn(mockTraceSegment);

    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mockRequestContext);

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback =
        mock(BiFunction.class);
    when(requestEndedCallback.apply(any(), any())).thenReturn(new Flow.ResultFlow<>(null));

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestEnded())).thenReturn(requestEndedCallback);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    LambdaAppSecHandler.processRequestEnd(span);

    verify(requestEndedCallback).apply(mockRequestContext, span);
    verify(mockTraceSegment).setTagTop(Tags.ASM_KEEP, true);
    verify(mockTraceSegment).setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
  }

  @Test
  void processRequestStartReturnsNullAndSkipsWafForNonHttpEvent() {
    String eventJson = "{\"Records\": [{\"body\": \"hello\"}]}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNull(result);
  }

  @Test
  void processRequestEndSetsUnsupportedEventTypeMetricForNonHttpEvent() {
    String eventJson = "{\"Records\": [{\"body\": \"hello\"}]}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setMetric(LambdaAppSecHandler.TAG_UNSUPPORTED_EVENT_TYPE, 1);
    verify(span, never()).setTag(eq(Tags.HTTP_URL), any(String.class));
    verify(span, never()).setTag(eq(Tags.HTTP_ROUTE), any(String.class));
    verify(span, never()).setTag(eq(Tags.HTTP_USER_AGENT), any(String.class));
  }

  @Test
  void processRequestEndSetsUnsupportedEventTypeMetricForNonByteArrayInputStreamEvent() {
    // A typed POJO handler event (not a ByteArrayInputStream) carries no raw HTTP payload AppSec
    // can analyze, so processRequestStart returns null without starting a WAF context but the span
    // must still be marked unsupported.
    setupMockCallbacks(new Callbacks());
    assertNull(LambdaAppSecHandler.processRequestStart("not a stream"));

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setMetric(LambdaAppSecHandler.TAG_UNSUPPORTED_EVENT_TYPE, 1);
    verify(span, never()).setTag(eq(Tags.HTTP_URL), any(String.class));
    verify(span, never()).setTag(eq(Tags.HTTP_ROUTE), any(String.class));
    verify(span, never()).setTag(eq(Tags.HTTP_USER_AGENT), any(String.class));
  }

  @Test
  void processRequestEndSetsHttpSpanTagsForApiGatewayV1RestEvent() {
    String eventJson =
        "{"
            + "\"resource\": \"/pets/{petId}\","
            + "\"path\": \"/pets/123\","
            + "\"httpMethod\": \"GET\","
            + "\"headers\": {\"Host\": \"api.example.com\", \"User-Agent\": \"curl/8.0\"},"
            + "\"requestContext\": {"
            + "  \"httpMethod\": \"GET\","
            + "  \"requestId\": \"req-1\","
            + "  \"identity\": {\"sourceIp\": \"1.2.3.4\"}"
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span, never()).setMetric(eq(LambdaAppSecHandler.TAG_UNSUPPORTED_EVENT_TYPE), anyInt());
    verify(span).setTag(Tags.HTTP_USER_AGENT, "curl/8.0");
    verify(span).setTag(Tags.HTTP_URL, "https://api.example.com/pets/123");
    verify(span).setTag(Tags.HTTP_ROUTE, "/pets/{petId}");
  }

  @Test
  void processRequestEndOmitsHttpRouteForAlbEvent() {
    String eventJson =
        "{\"httpMethod\": \"GET\",\"path\": \"/health\",\"headers\": {\"host\":"
            + " \"my-alb.example.com\", \"user-agent\":"
            + " \"ALB-HealthChecker/2.0\"},\"requestContext\": {\"elb\": {\"targetGroupArn\":"
            + " \"arn:aws:elasticloadbalancing:x\"}}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "https://my-alb.example.com/health");
    verify(span).setTag(Tags.HTTP_USER_AGENT, "ALB-HealthChecker/2.0");
    verify(span, never()).setTag(eq(Tags.HTTP_ROUTE), any(String.class));
  }

  @Test
  void processRequestEndOmitsHttpRouteForApiGatewayV2DefaultRoute() {
    String eventJson =
        "{"
            + "\"headers\": {\"host\": \"xyz.execute-api.us-east-1.amazonaws.com\"},"
            + "\"requestContext\": {"
            + "  \"routeKey\": \"$default\","
            + "  \"domainName\": \"xyz.execute-api.us-east-1.amazonaws.com\","
            + "  \"http\": {\"method\": \"GET\", \"path\": \"/anything\"}"
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span, never()).setTag(eq(Tags.HTTP_ROUTE), any(String.class));
  }

  @Test
  void processRequestEndSetsHttpRouteForApiGatewayV2NonDefaultRoute() {
    String eventJson =
        "{"
            + "\"headers\": {\"host\": \"xyz.execute-api.us-east-1.amazonaws.com\"},"
            + "\"requestContext\": {"
            + "  \"routeKey\": \"GET /pets/{petId}\","
            + "  \"domainName\": \"xyz.execute-api.us-east-1.amazonaws.com\","
            + "  \"http\": {\"method\": \"GET\", \"path\": \"/pets/123\"}"
            + "}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_ROUTE, "/pets/{petId}");
  }

  @Test
  void processRequestEndSetsHttpMethodForHttpEvent() {
    String eventJson =
        "{"
            + "\"resource\": \"/pets/{petId}\","
            + "\"path\": \"/pets/123\","
            + "\"httpMethod\": \"POST\","
            + "\"headers\": {\"Host\": \"api.example.com\"},"
            + "\"requestContext\": {\"httpMethod\": \"POST\", \"requestId\": \"req-1\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_METHOD, "POST");
  }

  @Test
  void processRequestEndTagsQueryStringSeparatelyAndKeepsItOutOfUrl() {
    // http.url must not carry the raw query string; the query is tagged as http.query.string so
    // QueryObfuscator can redact secrets before re-appending it to http.url.
    String eventJson =
        "{"
            + "\"resource\": \"/login\","
            + "\"path\": \"/login\","
            + "\"httpMethod\": \"GET\","
            + "\"headers\": {\"Host\": \"api.example.com\"},"
            + "\"queryStringParameters\": {\"password\": \"hunter2\"},"
            + "\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-1\"}"
            + "}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "https://api.example.com/login");
    verify(span).setTag(DDTags.HTTP_QUERY, "password=hunter2");
  }

  @Test
  void processRequestEndPrefersV2RawQueryStringPreservingRepeatedParams() {
    // API Gateway v2 flattens repeated params into queryStringParameters ("value": "one,two"), but
    // rawQueryString keeps them distinct. http.query.string must use the verbatim rawQueryString.
    String eventJson =
        "{\"version\": \"2.0\",\"rawQueryString\": \"value=one&value=two\","
            + "\"queryStringParameters\": {\"value\": \"one,two\"},"
            + "\"headers\": {\"host\": \"api.example.com\"},"
            + "\"requestContext\": {\"domainName\": \"api.example.com\","
            + " \"http\": {\"method\": \"GET\", \"path\": \"/items\"}}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "https://api.example.com/items");
    verify(span).setTag(DDTags.HTTP_QUERY, "value=one&value=two");
  }

  @Test
  void processRequestEndPreservesV2RawQueryStringEncoding() {
    // rawQueryString keeps the client's exact percent-encoding; reconstructing from the decoded map
    // would canonicalize it (space -> '+', etc.), corrupting exact URL search/grouping.
    String eventJson =
        "{\"version\": \"2.0\",\"rawQueryString\": \"q=hello%20world\","
            + "\"queryStringParameters\": {\"q\": \"hello world\"},"
            + "\"headers\": {\"host\": \"api.example.com\"},"
            + "\"requestContext\": {\"domainName\": \"api.example.com\","
            + " \"http\": {\"method\": \"GET\", \"path\": \"/search\"}}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(DDTags.HTTP_QUERY, "q=hello%20world");
  }

  @Test
  void v2RawQueryStringIsUsedForWafUri() {
    // The WAF URI must also see the verbatim raw query rather than the reconstructed one.
    String eventJson =
        "{\"version\": \"2.0\",\"rawQueryString\": \"value=one&value=two\","
            + "\"queryStringParameters\": {\"value\": \"one,two\"},"
            + "\"headers\": {\"host\": \"api.example.com\"},"
            + "\"requestContext\": {\"domainName\": \"api.example.com\","
            + " \"http\": {\"method\": \"GET\", \"path\": \"/items\"}}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    String[] capturedQuery = {null};
    String[] capturedRawQuery = {null};
    setupMockCallbacks(
        new Callbacks()
            .onMethodUri(
                (method, uri) -> {
                  capturedQuery[0] = uri.query();
                  capturedRawQuery[0] = uri.rawQuery();
                }));

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertEquals("value=one&value=two", capturedQuery[0]);
    assertEquals("value=one&value=two", capturedRawQuery[0]);
  }

  @Test
  void processRequestEndUsesFirstForwardedProtoValueForUrlScheme() {
    // A double-proxy hop can produce a comma-separated X-Forwarded-Proto; only the first value is
    // a valid scheme.
    String eventJson =
        "{\"resource\": \"/pets/{petId}\",\"path\": \"/pets/123\",\"httpMethod\":"
            + " \"GET\",\"headers\": {\"Host\": \"api.example.com\", \"X-Forwarded-Proto\": \"http,"
            + " https\"},\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-1\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "http://api.example.com/pets/123");
  }

  @Test
  void processRequestEndIncludesNonDefaultForwardedPortInUrl() {
    // A non-standard X-Forwarded-Port must be preserved in http.url (only the first hop is used).
    String eventJson =
        "{\"resource\": \"/pets/{petId}\",\"path\": \"/pets/123\",\"httpMethod\":"
            + " \"GET\",\"headers\": {\"Host\": \"api.example.com\", \"X-Forwarded-Port\": \"8443,"
            + " 443\"},\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-1\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "https://api.example.com:8443/pets/123");
  }

  @Test
  void processRequestEndOmitsDefaultForwardedPortFromUrl() {
    // The scheme's default port (443 for https) must not appear in http.url.
    String eventJson =
        "{\"resource\": \"/pets/{petId}\",\"path\": \"/pets/123\",\"httpMethod\":"
            + " \"GET\",\"headers\": {\"Host\": \"api.example.com\", \"X-Forwarded-Port\":"
            + " \"443\"},\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-1\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "https://api.example.com/pets/123");
  }

  @Test
  void processRequestEndDoesNotDuplicatePortWhenHostAlreadyHasOne() {
    // When the Host header already carries a port and X-Forwarded-Port repeats it, http.url must
    // not
    // end up with two ports (e.g. api.example.com:8443:8443).
    String eventJson =
        "{\"resource\": \"/pets/{petId}\",\"path\": \"/pets/123\",\"httpMethod\":"
            + " \"GET\",\"headers\": {\"Host\": \"api.example.com:8443\", \"X-Forwarded-Port\":"
            + " \"8443\"},\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-1\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "https://api.example.com:8443/pets/123");
  }

  @Test
  void processRequestEndKeepsPortFromBracketedIpv6Host() {
    // A bracketed IPv6 Host with an explicit port must be preserved verbatim and not gain a second
    // port from X-Forwarded-Port.
    String eventJson =
        "{\"resource\": \"/pets/{petId}\",\"path\": \"/pets/123\",\"httpMethod\":"
            + " \"GET\",\"headers\": {\"Host\": \"[::1]:8443\", \"X-Forwarded-Port\":"
            + " \"8443\"},\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-1\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);
    setupMockCallbacks(new Callbacks());
    LambdaAppSecHandler.processRequestStart(event);

    AgentSpan span = setupSpanForRequestEnd();
    LambdaAppSecHandler.processRequestEnd(span);

    verify(span).setTag(Tags.HTTP_URL, "https://[::1]:8443/pets/123");
  }

  @SuppressWarnings("unchecked")
  private AgentSpan setupSpanForRequestEnd() {
    AppSecContext mockAppSecContext = mock(AppSecContext.class);
    when(mockAppSecContext.isManuallyKept()).thenReturn(false);
    TraceSegment mockTraceSegment = mock(TraceSegment.class);
    RequestContext mockRequestContext = mock(RequestContext.class);
    when(mockRequestContext.getData(RequestContextSlot.APPSEC)).thenReturn(mockAppSecContext);
    when(mockRequestContext.getTraceSegment()).thenReturn(mockTraceSegment);

    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mockRequestContext);

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback =
        mock(BiFunction.class);
    when(requestEndedCallback.apply(any(), any())).thenReturn(new Flow.ResultFlow<>(null));

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestEnded())).thenReturn(requestEndedCallback);

    AgentTracer.TracerAPI mockTracer = mock(AgentTracer.TracerAPI.class);
    when(mockTracer.getCallbackProvider(RequestContextSlot.APPSEC))
        .thenReturn(mockCallbackProvider);
    AgentTracer.forceRegister(mockTracer);

    return span;
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
  @SuppressWarnings("unchecked")
  void processRequestStartHandlesNullHeaderDoneCallbackGracefully() {
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
    when(mockCallbackProvider.getCallback(EVENTS.requestHeaderDone())).thenReturn(null);
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
  @SuppressWarnings("unchecked")
  void processRequestStartHandlesNullPathParamsCallbackGracefully() {
    String eventJson =
        "{\"path\": \"/test\", \"pathParameters\": {\"id\": \"42\"}, \"requestContext\":"
            + " {\"httpMethod\": \"GET\"}}";
    ByteArrayInputStream event = createInputStream(eventJson);

    Object mockAppSecContext = new Object();
    Supplier<Flow<Object>> requestStartedCallback = mock(Supplier.class);
    when(requestStartedCallback.get()).thenReturn(new Flow.ResultFlow<>(mockAppSecContext));

    Function<RequestContext, Flow<Void>> headerDoneCallback = mock(Function.class);
    when(headerDoneCallback.apply(any())).thenReturn(new Flow.ResultFlow<>(null));

    CallbackProvider mockCallbackProvider = mock(CallbackProvider.class);
    when(mockCallbackProvider.getCallback(EVENTS.requestStarted()))
        .thenReturn(requestStartedCallback);
    when(mockCallbackProvider.getCallback(EVENTS.requestMethodUriRaw())).thenReturn(null);
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

    AgentSpanContext result = LambdaAppSecHandler.processRequestStart(event);

    assertNotNull(result);
    assertInstanceOf(TagContext.class, result);
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
    assertNull(ctx.getData(RequestContextSlot.IAST));
  }

  @Test
  void temporaryRequestContextIsNotCreatedWhenAppSecContextIsNull() {
    assertNull(captureTemporaryRequestContext(null));
  }

  @Test
  void temporaryRequestContextNoOpMethodsReturnExpectedDefaults() {
    RequestContext ctx = captureTemporaryRequestContext(new Object());

    assertNotNull(ctx);
    assertEquals(TraceSegment.NoOp.INSTANCE, ctx.getTraceSegment());
    assertNull(ctx.getBlockResponseFunction());
    assertNull(ctx.getOrCreateMetaStructTop("key", k -> new Object()));
    assertNull(ctx.getClientIpAddressData());
    assertDoesNotThrow(() -> ctx.setBlockResponseFunction(mock(BlockResponseFunction.class)));
    assertDoesNotThrow(() -> ctx.setClientIpAddressData(mock(ClientIpAddressData.class)));
    assertDoesNotThrow(ctx::close);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(null);
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200}");
    setupMockResponseCallbacks(null, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    // reaches the requestContext-null guard and returns without firing callbacks
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
            "{\"statusCode\": 0, \"headers\": {\"content-type\": \"text/plain\"}, \"body\":"
                + " \"hello\"}");
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    ByteArrayOutputStream result = createOutputStream("{\"statusCode\": 200, \"body\": \"ok\"}");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals(200, capturedStatus[0]);
  }

  @Test
  void processResponseDataExtractsStatusCodeAsIntegerFromDouble() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    ByteArrayOutputStream result =
        createOutputStream("{\"statusCode\": 404.0, \"body\": \"not found\"}");
    Integer[] capturedStatus = {null};
    AgentSpan span =
        setupMockResponseCallbacks(status -> capturedStatus[0] = status, null, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals(404, capturedStatus[0]);
  }

  @Test
  void processResponseDataHandlesNonNumericStatusCode() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/json\", \"x-custom\":"
            + " \"val\", \"content-length\": \"42\", \"set-cookie\": \"a=1\"}}";
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"Content-Type\": \"text/html\", \"CONTENT-LENGTH\":"
            + " \"10\"}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("text/html", capturedHeaders.get("content-type"));
    assertEquals("10", capturedHeaders.get("content-length"));
  }

  @Test
  void processResponseDataMergesMultiValueHeadersWithSingleValueHeaders() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/html\"},"
            + " \"multiValueHeaders\": {\"content-encoding\": [\"gzip\", \"br\"]}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("text/html", capturedHeaders.get("content-type"));
    assertEquals("gzip, br", capturedHeaders.get("content-encoding"));
  }

  @Test
  void processResponseDataHandlesEmptyHeaders() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/json\"}, \"body\":"
            + " \"{\\\"key\\\": \\\"value\\\"}\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("value", ((Map<?, ?>) capturedBody[0]).get("key"));
  }

  @Test
  void processResponseDataHandlesNonJsonBodyAsRawString() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/plain\"}, \"body\": \"plain"
            + " text\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("plain text", capturedBody[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void processResponseDataHandlesBase64EncodedBody() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    ByteArrayOutputStream result =
        createOutputStream("{\"statusCode\": 200, \"body\": \"not json {\"}");
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("not json {", capturedBody[0]);
  }

  @Test
  void processResponseDataDropsMalformedBodyUnderJsonContentType() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    // Explicit application/json content-type but the body is not valid JSON: it is malformed for
    // its declared type, so responseBody must not fire (dropped, matching the extension) rather
    // than being forwarded as a raw string.
    ByteArrayOutputStream result =
        createOutputStream(
            "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/json\"},"
                + " \"body\": \"not json {\"}");
    String[] capturedBody = {"NOT_CALLED"};
    AgentSpan span =
        setupMockResponseCallbacks(
            null, null, null, body -> capturedBody[0] = String.valueOf(body));
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("NOT_CALLED", capturedBody[0]);
  }

  @Test
  void processResponseDataKeepsTextPlainJsonBodyAsRawString() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    // text/plain body that happens to be valid JSON must NOT be JSON-parsed; it is kept as the raw
    // string so string-based WAF rules can still scan it (matches the extension).
    ByteArrayOutputStream result =
        createOutputStream(
            "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/plain\"},"
                + " \"body\": \"{\\\"a\\\": 1}\"}");
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("{\"a\": 1}", capturedBody[0]);
  }

  // --- Event ordering ---

  @Test
  void processResponseDataFiresEventsInCorrectOrder() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/json\"}, \"body\":"
            + " \"{\\\"k\\\": \\\"v\\\"}\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    List<String> order = new ArrayList<>();

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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"application/javascript\"},"
            + " \"body\": \"{\\\"key\\\": \\\"val\\\"}\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertInstanceOf(Map.class, capturedBody[0]);
    assertEquals("val", ((Map<?, ?>) capturedBody[0]).get("key"));
  }

  @Test
  void processResponseDataParsesUrlEncodedBody() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\":"
            + " \"application/x-www-form-urlencoded\"}, \"body\": \"a=1&b=hello+world\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertInstanceOf(Map.class, capturedBody[0]);
    @SuppressWarnings("unchecked")
    Map<String, List<String>> body = (Map<String, List<String>>) capturedBody[0];
    assertEquals(Collections.singletonList("1"), body.get("a"));
    assertEquals(Collections.singletonList("hello world"), body.get("b"));
  }

  @Test
  void processResponseDataMultipartBodyIsKeptAsRawString() {
    // multipart/form-data response bodies are not structurally parsed; the raw payload is forwarded
    // so string-based WAF rules can still scan it.
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String multipartBody =
        "--BOUNDARY\r\n"
            + "Content-Disposition: form-data; name=\"field1\"\r\n"
            + "\r\n"
            + "value1\r\n"
            + "--BOUNDARY--\r\n";
    String json =
        "{\"statusCode\": 200, "
            + "\"headers\": {\"content-type\": \"multipart/form-data; boundary=BOUNDARY\"}, "
            + "\"body\": \""
            + escapeJson(multipartBody)
            + "\"}";
    ByteArrayOutputStream result = createOutputStream(json);
    Object[] capturedBody = {null};
    AgentSpan span = setupMockResponseCallbacks(null, null, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals(multipartBody, capturedBody[0]);
  }

  @Test
  void processResponseDataSkipsMultiValueHeadersEntryWithNonListValue() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/html\"},"
            + " \"multiValueHeaders\": {\"x-scalar\": \"not-a-list\", \"x-valid\": [\"v1\","
            + " \"v2\"]}}";
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json =
        "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/html\"},"
            + " \"multiValueHeaders\": {\"content-type\": [\"application/json\","
            + " \"charset=utf-8\"]}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span = setupMockResponseCallbacks(null, capturedHeaders::put, null, null);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("application/json, charset=utf-8", capturedHeaders.get("content-type"));
  }

  // --- Error handling ---

  @Test
  void processResponseDataFallsBackToTextPlainForMalformedJsonResponse() {
    // Not valid API-GW-formatted JSON and not valid JSON at all, so extractResponseData returns
    // null and the OBJECT_ADAPTER.fromJson retry in the fallback path also throws — the raw
    // string must be surfaced as the response body with a text/plain content-type.
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    ByteArrayOutputStream result = createOutputStream("{not valid json");
    Object[] capturedBody = {null};
    Map<String, String> capturedHeaders = new HashMap<>();
    AgentSpan span =
        setupMockResponseCallbacks(
            null, capturedHeaders::put, null, body -> capturedBody[0] = body);
    LambdaAppSecHandler.processResponseData(span, result);
    assertEquals("{not valid json", capturedBody[0]);
    assertEquals("text/plain", capturedHeaders.get("content-type"));
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
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
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

  @Test
  void processResponseDataSkipsResponseStartedWhenCallbackIsNull() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json = "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/plain\"}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Map<String, String> capturedHeaders = new HashMap<>();
    boolean[] headerDoneCalled = {false};
    AgentSpan span =
        setupMockResponseCallbacks(
            null, capturedHeaders::put, () -> headerDoneCalled[0] = true, null);
    assertDoesNotThrow(() -> LambdaAppSecHandler.processResponseData(span, result));
    assertTrue(headerDoneCalled[0]);
  }

  @Test
  void processResponseDataSkipsResponseHeaderWhenCallbackIsNull() {
    LambdaAppSecHandler.setCurrentTriggerType(
        LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST);
    String json = "{\"statusCode\": 200, \"headers\": {\"content-type\": \"text/plain\"}}";
    ByteArrayOutputStream result = createOutputStream(json);
    Integer[] capturedStatus = {null};
    boolean[] headerDoneCalled = {false};
    AgentSpan span =
        setupMockResponseCallbacks(
            status -> capturedStatus[0] = status, null, () -> headerDoneCalled[0] = true, null);
    assertDoesNotThrow(() -> LambdaAppSecHandler.processResponseData(span, result));
    assertEquals(200, capturedStatus[0]);
    assertTrue(headerDoneCalled[0]);
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
