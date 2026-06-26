import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.junit.utils.config.WithConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "_HANDLER", value = "Handler", env = true, addPrefix = false)
abstract class LambdaHandlerInstrumentationTest extends AbstractInstrumentationTest {

  static final String REQUEST_ID = "test-request-id";

  // Object to avoid bootstrap class in field type (TestClassShadowingExtension check)
  Object ig;

  boolean appSecStarted;
  String capturedMethod;
  String capturedPath;
  Map<String, String> capturedHeaders;
  Object capturedBody;
  boolean appSecEnded;

  Integer capturedResponseStatus;
  Map<String, String> capturedResponseHeaders;
  Object capturedResponseBody;
  boolean responseHeaderDoneCalled;

  abstract int version();

  abstract String operation();

  @BeforeEach
  void setUpAppSec() {
    SubscriptionService ss =
        (SubscriptionService) AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC);
    ig = ss;
    ActiveSubsystems.APPSEC_ACTIVE = true;

    appSecStarted = false;
    capturedMethod = null;
    capturedPath = null;
    capturedHeaders = new HashMap<>();
    capturedBody = null;
    appSecEnded = false;
    capturedResponseStatus = null;
    capturedResponseHeaders = new HashMap<>();
    capturedResponseBody = null;
    responseHeaderDoneCalled = false;

    ss.registerCallback(
        EVENTS.requestStarted(),
        (Supplier<Flow<Object>>)
            () -> {
              appSecStarted = true;
              return new Flow.ResultFlow<>(new Object());
            });
    ss.registerCallback(
        EVENTS.requestMethodUriRaw(),
        (TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>)
            (ctx2, method2, uri) -> {
              capturedMethod = method2;
              capturedPath = uri.path();
              return Flow.ResultFlow.empty();
            });
    ss.registerCallback(
        EVENTS.requestHeader(),
        (TriConsumer<RequestContext, String, String>)
            (ctx2, name, value) -> capturedHeaders.put(name, value));
    ss.registerCallback(
        EVENTS.requestHeaderDone(),
        (Function<RequestContext, Flow<Void>>) ctx2 -> Flow.ResultFlow.empty());
    ss.registerCallback(
        EVENTS.requestBodyProcessed(),
        (BiFunction<RequestContext, Object, Flow<Void>>)
            (ctx2, body) -> {
              capturedBody = body;
              return Flow.ResultFlow.empty();
            });
    ss.registerCallback(
        EVENTS.requestEnded(),
        (BiFunction<RequestContext, IGSpanInfo, Flow<Void>>)
            (ctx2, spanInfo) -> {
              appSecEnded = true;
              return Flow.ResultFlow.empty();
            });

    ss.registerCallback(
        EVENTS.responseStarted(),
        (BiFunction<RequestContext, Integer, Flow<Void>>)
            (ctx2, status) -> {
              capturedResponseStatus = status;
              return Flow.ResultFlow.empty();
            });
    ss.registerCallback(
        EVENTS.responseHeader(),
        (TriConsumer<RequestContext, String, String>)
            (ctx2, name, value) -> capturedResponseHeaders.put(name, value));
    ss.registerCallback(
        EVENTS.responseHeaderDone(),
        (Function<RequestContext, Flow<Void>>)
            ctx2 -> {
              responseHeaderDoneCalled = true;
              return Flow.ResultFlow.empty();
            });
    ss.registerCallback(
        EVENTS.responseBody(),
        (BiFunction<RequestContext, Object, Flow<Void>>)
            (ctx2, body) -> {
              capturedResponseBody = body;
              return Flow.ResultFlow.empty();
            });
  }

  @AfterEach
  void cleanUpAppSec() {
    ((SubscriptionService) ig).reset();
    ActiveSubsystems.APPSEC_ACTIVE = false;
  }

  private Context newContext() {
    return new TestContext(REQUEST_ID);
  }

  @Test
  void testLambdaStreamingHandler() throws IOException {
    ByteArrayInputStream input = new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreaming().handleRequest(input, output, newContext());

    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void serverlessInvocationSpanResourceResetAfterHttpFrameworkOverwrite() throws IOException {
    ByteArrayInputStream input = new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreamingSimulatesHttpFrameworkResource().handleRequest(input, output, newContext());

    assertTraces(
        trace(
            span()
                .resourceName(name -> operation().equals(name.toString()))
                .type(DDSpanTypes.SERVERLESS)
                .error(false)));
  }

  @Test
  void testStreamingHandlerWithError() {
    ByteArrayInputStream input = new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    assertThrows(
        Error.class,
        () -> new HandlerStreamingWithError().handleRequest(input, output, newContext()));

    assertTraces(
        trace(
            span()
                .type(DDSpanTypes.SERVERLESS)
                .error(true)
                .tags(
                    defaultTags(),
                    tag("request_id", is(REQUEST_ID)),
                    error(Error.class, "Some error"))));
  }

  @Test
  void appSecCallbacksAreInvokedForApiGatewayV1Event() throws IOException {
    String eventJson =
        "{"
            + "\"path\": \"/api/users/123\","
            + "\"headers\": {\"content-type\": \"application/json\","
            + "              \"x-forwarded-for\": \"203.0.113.1\"},"
            + "\"body\": \"{\\\"key\\\": \\\"value\\\"}\","
            + "\"requestContext\": {"
            + "  \"httpMethod\": \"GET\","
            + "  \"requestId\": \"req-abc\","
            + "  \"identity\": {\"sourceIp\": \"203.0.113.1\"}"
            + "}"
            + "}";

    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreaming().handleRequest(input, output, newContext());

    assertTrue(appSecStarted);
    assertEquals("GET", capturedMethod);
    assertEquals("/api/users/123", capturedPath);
    assertEquals("application/json", capturedHeaders.get("content-type"));
    assertTrue(capturedBody instanceof Map);
    assertTrue(appSecEnded);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void appSecCallbacksAreInvokedForApiGatewayV2HttpEvent() throws IOException {
    String eventJson =
        "{"
            + "\"version\": \"2.0\","
            + "\"headers\": {\"content-type\": \"application/json\","
            + "              \"accept\": \"application/json\"},"
            + "\"cookies\": [\"session=abc123\"],"
            + "\"body\": \"{\\\"key\\\": \\\"value\\\"}\","
            + "\"requestContext\": {"
            + "  \"http\": {"
            + "    \"method\": \"POST\","
            + "    \"path\": \"/api/items\","
            + "    \"sourceIp\": \"198.51.100.1\""
            + "  },"
            + "  \"domainName\": \"api.example.com\""
            + "}"
            + "}";

    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreaming().handleRequest(input, output, newContext());

    assertTrue(appSecStarted);
    assertEquals("POST", capturedMethod);
    assertEquals("/api/items", capturedPath);
    assertEquals("application/json", capturedHeaders.get("content-type"));
    assertEquals("session=abc123", capturedHeaders.get("cookie"));
    assertTrue(capturedBody instanceof Map);
    assertTrue(appSecEnded);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void appSecCallbacksAreNotInvokedWhenAppSecIsDisabled() throws IOException {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    String eventJson =
        "{"
            + "\"path\": \"/api/test\","
            + "\"requestContext\": {\"httpMethod\": \"GET\", \"requestId\": \"req-xyz\"}"
            + "}";
    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreaming().handleRequest(input, output, newContext());

    assertFalse(appSecStarted);
    assertNull(capturedMethod);
    assertFalse(appSecEnded);
    assertNull(capturedResponseStatus);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void responseCallbacksAreInvokedForJsonEncodedResponse() throws IOException {
    String eventJson =
        "{"
            + "\"path\": \"/api/test\","
            + "\"headers\": {\"content-type\": \"application/json\"},"
            + "\"requestContext\": {"
            + "  \"httpMethod\": \"GET\","
            + "  \"identity\": {\"sourceIp\": \"127.0.0.1\"}"
            + "}"
            + "}";

    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreamingWithApiGwResponse().handleRequest(input, output, newContext());

    assertEquals(200, (int) capturedResponseStatus);
    assertEquals("application/json", capturedResponseHeaders.get("content-type"));
    assertEquals("custom-val", capturedResponseHeaders.get("x-custom"));
    assertTrue(capturedResponseBody instanceof Map);
    assertEquals("ok", ((Map<?, ?>) capturedResponseBody).get("result"));
    assertTrue(responseHeaderDoneCalled);
    assertTrue(appSecEnded);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void responseCallbacksReceiveCorrectDataFor404Response() throws IOException {
    String eventJson =
        "{"
            + "\"path\": \"/missing\","
            + "\"requestContext\": {"
            + "  \"httpMethod\": \"GET\""
            + "}"
            + "}";

    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreamingWith404Response().handleRequest(input, output, newContext());

    assertEquals(404, (int) capturedResponseStatus);
    assertEquals("text/html", capturedResponseHeaders.get("content-type"));
    assertEquals("Not Found", capturedResponseBody);
    assertTrue(appSecEnded);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void responseCallbacksApplyFallbackForLambdaUrlWithNonApiGatewayResponse() throws IOException {
    // A Lambda Function URL handler returning plain JSON (no statusCode/headers/body structure)
    // should trigger the fallback: no responseStarted (status unknown), content-type:
    // application/json, full JSON as body.
    String eventJson =
        "{"
            + "\"version\": \"2.0\","
            + "\"rawPath\": \"/\","
            + "\"headers\": {\"host\": \"example.lambda-url.us-east-1.on.aws\"},"
            + "\"requestContext\": {"
            + "  \"domainName\": \"example.lambda-url.us-east-1.on.aws\","
            + "  \"http\": {"
            + "    \"method\": \"GET\","
            + "    \"path\": \"/\","
            + "    \"sourceIp\": \"1.2.3.4\""
            + "  }"
            + "}"
            + "}";
    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreamingWithRawJson().handleRequest(input, output, newContext());

    assertNull(capturedResponseStatus); // no responseStarted for status-less fallback
    assertEquals("application/json", capturedResponseHeaders.get("content-type"));
    assertTrue(capturedResponseBody instanceof Map);
    assertEquals("hello", ((Map<?, ?>) capturedResponseBody).get("result"));
    assertTrue(responseHeaderDoneCalled);
    assertTrue(appSecEnded);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void responseCallbacksSkipNonApiGatewayResponseForNonHttpEvent() throws IOException {
    // When the trigger type cannot be determined (non-JSON or non-HTTP event),
    // response callbacks must not fire even if the response is valid JSON.
    ByteArrayInputStream input = new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreamingWithRawJson().handleRequest(input, output, newContext());

    assertNull(capturedResponseStatus);
    assertTrue(capturedResponseHeaders.isEmpty());
    assertNull(capturedResponseBody);
    assertFalse(responseHeaderDoneCalled);
    assertTrue(appSecEnded);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void responseAndRequestCallbacksAreBothInvoked() throws IOException {
    String eventJson =
        "{"
            + "\"path\": \"/api/users/123\","
            + "\"headers\": {\"content-type\": \"application/json\"},"
            + "\"body\": \"{\\\"key\\\": \\\"value\\\"}\","
            + "\"requestContext\": {"
            + "  \"httpMethod\": \"POST\","
            + "  \"requestId\": \"req-order-1\","
            + "  \"identity\": {\"sourceIp\": \"10.0.0.1\"}"
            + "}"
            + "}";

    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreamingWithApiGwResponse().handleRequest(input, output, newContext());

    assertTrue(appSecStarted);
    assertEquals("POST", capturedMethod);
    assertEquals("/api/users/123", capturedPath);
    assertTrue(capturedBody instanceof Map);

    assertEquals(200, (int) capturedResponseStatus);
    assertEquals("application/json", capturedResponseHeaders.get("content-type"));
    assertTrue(capturedResponseBody instanceof Map);

    assertTrue(appSecEnded);
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void responseCallbacksFireBeforeRequestEnded() throws IOException {
    List<String> callOrder = new ArrayList<>();

    // Reset and re-register to capture ordering
    SubscriptionService ss = (SubscriptionService) ig;
    ss.reset();

    ss.registerCallback(
        EVENTS.requestStarted(),
        (Supplier<Flow<Object>>) () -> new Flow.ResultFlow<>(new Object()));
    ss.registerCallback(
        EVENTS.responseStarted(),
        (BiFunction<RequestContext, Integer, Flow<Void>>)
            (ctx2, status) -> {
              callOrder.add("responseStarted");
              return Flow.ResultFlow.empty();
            });
    ss.registerCallback(
        EVENTS.responseHeaderDone(),
        (Function<RequestContext, Flow<Void>>)
            ctx2 -> {
              callOrder.add("responseHeaderDone");
              return Flow.ResultFlow.empty();
            });
    ss.registerCallback(
        EVENTS.responseBody(),
        (BiFunction<RequestContext, Object, Flow<Void>>)
            (ctx2, body) -> {
              callOrder.add("responseBody");
              return Flow.ResultFlow.empty();
            });
    ss.registerCallback(
        EVENTS.requestEnded(),
        (BiFunction<RequestContext, IGSpanInfo, Flow<Void>>)
            (ctx2, spanInfo) -> {
              callOrder.add("requestEnded");
              return Flow.ResultFlow.empty();
            });

    String eventJson =
        "{"
            + "\"path\": \"/api/test\","
            + "\"requestContext\": {"
            + "  \"httpMethod\": \"GET\","
            + "  \"requestId\": \"req-order-2\""
            + "}"
            + "}";
    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new HandlerStreamingWithApiGwResponse().handleRequest(input, output, newContext());

    assertTrue(callOrder.contains("responseStarted"));
    assertTrue(callOrder.contains("responseHeaderDone"));
    assertTrue(callOrder.contains("responseBody"));
    assertTrue(callOrder.contains("requestEnded"));
    assertTrue(callOrder.indexOf("responseStarted") < callOrder.indexOf("requestEnded"));
    assertTrue(callOrder.indexOf("responseHeaderDone") < callOrder.indexOf("requestEnded"));
    assertTrue(callOrder.indexOf("responseBody") < callOrder.indexOf("requestEnded"));
    assertTraces(
        trace(span().type(DDSpanTypes.SERVERLESS).error(false)));
  }

  @Test
  void responseCallbacksReceiveNoDataWhenHandlerThrows() {
    ByteArrayInputStream input = new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    assertThrows(
        Error.class,
        () -> new HandlerStreamingWithError().handleRequest(input, output, newContext()));

    assertNull(capturedResponseStatus, "response status should not be set when handler throws");
    assertNull(capturedResponseBody, "response body should not be set when handler throws");
    assertTraces(
        trace(
            span()
                .type(DDSpanTypes.SERVERLESS)
                .error(true)
                .tags(
                    defaultTags(),
                    tag("request_id", is(REQUEST_ID)),
                    error(Error.class, "Some error"))));
  }

  private static final class TestContext implements Context {
    private final String requestId;

    TestContext(String requestId) {
      this.requestId = requestId;
    }

    @Override
    public String getAwsRequestId() {
      return requestId;
    }

    @Override
    public String getLogGroupName() {
      return null;
    }

    @Override
    public String getLogStreamName() {
      return null;
    }

    @Override
    public String getFunctionName() {
      return null;
    }

    @Override
    public String getFunctionVersion() {
      return null;
    }

    @Override
    public String getInvokedFunctionArn() {
      return null;
    }

    @Override
    public CognitoIdentity getIdentity() {
      return null;
    }

    @Override
    public ClientContext getClientContext() {
      return null;
    }

    @Override
    public int getRemainingTimeInMillis() {
      return 0;
    }

    @Override
    public int getMemoryLimitInMB() {
      return 0;
    }

    @Override
    public LambdaLogger getLogger() {
      return null;
    }
  }
}
