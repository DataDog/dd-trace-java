package com.datadog.aiguard;

import static com.datadog.aiguard.AIGuardInternal.ACTION_TAG;
import static com.datadog.aiguard.AIGuardInternal.META_STRUCT_TAG;
import static com.datadog.aiguard.AIGuardInternal.REASON_TAG;
import static com.datadog.aiguard.AIGuardInternal.TARGET_TAG;
import static com.datadog.aiguard.AIGuardInternal.TOOL_TAG;
import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static datadog.trace.api.aiguard.AIGuard.Action.ABORT;
import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW;
import static datadog.trace.api.aiguard.AIGuard.Action.DENY;
import static datadog.trace.api.aiguard.AIGuard.ContentPart.Type.IMAGE_URL;
import static datadog.trace.api.aiguard.AIGuard.ContentPart.Type.TEXT;
import static datadog.trace.api.aiguard.AIGuard.Options.DEFAULT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_CLIENT_IP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_USER_AGENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.NETWORK_CLIENT_IP;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeEnvConfig;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.common.version.VersionInfo;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.aiguard.AIGuard.AIGuardAbortError;
import datadog.trace.api.aiguard.AIGuard.AIGuardClientError;
import datadog.trace.api.aiguard.AIGuard.Action;
import datadog.trace.api.aiguard.AIGuard.ContentPart;
import datadog.trace.api.aiguard.AIGuard.Evaluation;
import datadog.trace.api.aiguard.AIGuard.Message;
import datadog.trace.api.aiguard.AIGuard.Options;
import datadog.trace.api.aiguard.AIGuard.ToolCall;
import datadog.trace.api.aiguard.AIGuardTestHelper;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.telemetry.MetricCollector.Metric;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.tabletest.junit.TableTest;

@WithConfig(key = "SERVICE", value = "ai_guard_test", env = true)
@WithConfig(key = "ENV", value = "test", env = true)
class AIGuardInternalTests {

  private static final HttpUrl URL =
      HttpUrl.parse("https://app.datadoghq.com/api/v2/ai-guard/evaluate");

  private static final Map<String, String> HEADERS = new HashMap<>();

  static {
    HEADERS.put("DD-API-KEY", "api");
    HEADERS.put("DD-APPLICATION-KEY", "app");
    HEADERS.put("DD-AI-GUARD-VERSION", VersionInfo.VERSION);
    HEADERS.put("DD-AI-GUARD-SOURCE", "SDK");
    HEADERS.put("DD-AI-GUARD-LANGUAGE", "jvm");
  }

  private static final Moshi MOSHI = new Moshi.Builder().build();

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .setDefaultPropertyInclusion(JsonInclude.Value.construct(NON_NULL, NON_NULL));

  private static final List<Message> TOOL_CALL =
      asList(
          Message.message("system", "You are a beautiful AI assistant"),
          Message.message("user", "What is 2 + 2"),
          Message.assistant(
              ToolCall.toolCall("call_1", "calc", "{ \"operator\": \"+\", \"args\": [2, 2] }")));

  private static final List<Message> TOOL_OUTPUT = concat(TOOL_CALL, Message.tool("call_1", "5"));

  private static final List<Message> PROMPT =
      concat(TOOL_OUTPUT, Message.message("assistant", "2 + 2 is 5"), Message.message("user", ""));

  private AgentTracer.TracerAPI originalTracer;
  private AgentSpan span;
  private AgentSpan localRootSpan;
  private Map<String, Object> receivedMeta;
  private Request request;

  @BeforeEach
  void setup() {
    originalTracer = AgentTracer.get();

    span = mock(AgentSpan.class);
    localRootSpan = mock(AgentSpan.class);
    when(span.getLocalRootSpan()).thenReturn(localRootSpan);
    doAnswer(
            invocation -> {
              receivedMeta = invocation.getArgument(1);
              return span;
            })
        .when(span)
        .setMetaStruct(eq(META_STRUCT_TAG), any());
    AgentTracer.SpanBuilder builder = mock(AgentTracer.SpanBuilder.class);
    when(builder.start()).thenReturn(span);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.buildSpan(anyString(), anyString())).thenReturn(builder);
    when(tracer.activateSpan(any())).thenReturn(mock(ContextScope.class));
    AgentTracer.forceRegister(tracer);

    drainAIGuard();
  }

  @AfterEach
  void cleanup() {
    AgentTracer.forceRegister(originalTracer);
    AIGuardInternal.uninstall();
  }

  @TableTest({
    "scenario          | apiKey   | appKey  ",
    "app key missing   | 'apiKey' |         ",
    "app key empty     | 'apiKey' | ''      ",
    "api key missing   |          | 'appKey'",
    "api key empty     | ''       | 'appKey'",
    "both keys missing |          |         ",
    "both keys empty   | ''       | ''      "
  })
  void testMissingApiAppKeys(String apiKey, String appKey) {
    if (apiKey != null && !apiKey.isEmpty()) {
      injectEnvConfig("API_KEY", apiKey);
    }
    if (appKey != null && !appKey.isEmpty()) {
      injectEnvConfig("APP_KEY", appKey);
    }

    assertThrows(AIGuardInternal.BadConfigurationException.class, AIGuardInternal::install);
  }

  @TableTest({
    "scenario        | endpoint       | site            | expected                                            ",
    "custom endpoint | 'https://test' |                 | 'https://test/evaluate'                             ",
    "default site    |                |                 | 'https://app.datadoghq.com/api/v2/ai-guard/evaluate'",
    "datadoghq site  |                | 'datadoghq.com' | 'https://app.datadoghq.com/api/v2/ai-guard/evaluate'",
    "datad0g site    |                | 'datad0g.com'   | 'https://app.datad0g.com/api/v2/ai-guard/evaluate'  "
  })
  @WithConfig(key = "API_KEY", value = "api", env = true)
  @WithConfig(key = "APP_KEY", value = "app", env = true)
  void testEndpointDiscovery(String endpoint, String site, String expected) {
    if (endpoint != null) {
      injectEnvConfig("AI_GUARD_ENDPOINT", endpoint);
    } else {
      removeEnvConfig("AI_GUARD_ENDPOINT");
    }
    if (site != null) {
      injectEnvConfig("SITE", site);
    } else {
      removeEnvConfig("SITE");
    }

    AIGuardInternal.install();

    AIGuardInternal internal = (AIGuardInternal) AIGuardTestHelper.installedEvaluator();
    assertEquals(expected, internal.url.toString());
  }

  @ParameterizedTest
  @MethodSource("testEvaluateArguments")
  void testEvaluate(TestSuite suite) throws IOException, JSONException {
    boolean throwAbortError = suite.blocking && suite.action != ALLOW;
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", suite.action);
    attributes.put("reason", suite.reason);
    attributes.put("tags", suite.tags);
    attributes.put("tag_probs", suite.tagProbabilities);
    attributes.put("is_blocking_enabled", suite.blocking);
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));
    Options options = new Options().block(suite.blocking);

    if (throwAbortError) {
      AIGuardAbortError error =
          assertThrows(AIGuardAbortError.class, () -> aiguard.evaluate(suite.messages, options));
      assertEquals(suite.action, error.getAction());
      assertEquals(suite.reason, error.getReason());
      assertEquals(suite.tags, error.getTags());
      assertEquals(suite.tagProbabilities, error.getTagProbabilities());
      assertEquals(emptyList(), error.getSds());
    } else {
      Evaluation eval = aiguard.evaluate(suite.messages, options);
      assertEquals(suite.action, eval.getAction());
      assertEquals(suite.reason, eval.getReason());
      assertEquals(suite.tags, eval.getTags());
      assertEquals(suite.tagProbabilities, eval.getTagProbabilities());
      assertEquals(emptyList(), eval.getSds());
    }

    verify(span).setTag(TARGET_TAG, suite.target);
    verify(localRootSpan).setTag(Tags.AI_GUARD_KEEP, true);
    verify(localRootSpan).setTag(Tags.AI_GUARD_EVENT, true);
    verify(localRootSpan).setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.AI_GUARD);
    if ("tool".equals(suite.target)) {
      verify(span).setTag(TOOL_TAG, "calc");
    }
    verify(span).setTag(ACTION_TAG, suite.action);
    verify(span).setTag(REASON_TAG, suite.reason);
    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any(Map.class));
    if (throwAbortError) {
      verify(span).addThrowable(any(AIGuardAbortError.class));
    }

    assertMeta(receivedMeta, suite);
    assertRequest(request, suite.messages);
    // no redaction_replacements in these responses, and redaction is on by default
    assertTelemetry(
        "requests",
        "action:" + suite.action,
        "block:" + throwAbortError,
        "error:false",
        "redacted:false");
  }

  static Stream<Arguments> testEvaluateArguments() {
    Map<String, Double> denyProbabilities = new HashMap<>();
    denyProbabilities.put("deny_everything", 0.2D);
    denyProbabilities.put("test_deny", 0.8D);
    Map<String, Double> abortProbabilities = new HashMap<>();
    abortProbabilities.put("alarm_tag", 0.1D);
    abortProbabilities.put("abort_everything", 0.9D);
    List<TestSuite> suites = new ArrayList<>();
    for (TestSuite.Verdict verdict :
        asList(
            new TestSuite.Verdict(ALLOW, "Go ahead", emptyMap()),
            new TestSuite.Verdict(DENY, "Nope", denyProbabilities),
            new TestSuite.Verdict(ABORT, "Kill it with fire", abortProbabilities))) {
      for (boolean blocking : new boolean[] {true, false}) {
        suites.add(new TestSuite(verdict, blocking, "tool call", "tool", TOOL_CALL));
        suites.add(new TestSuite(verdict, blocking, "tool output", "tool", TOOL_OUTPUT));
        suites.add(new TestSuite(verdict, blocking, "prompt", "prompt", PROMPT));
      }
    }
    return suites.stream().map(Arguments::arguments);
  }

  @TableTest({
    "scenario                                | block | remoteBlocking | shouldBlock",
    "default options and remote blocking     |       | true           | true       ",
    "default options and remote not blocking |       | false          | false      ",
    "local blocking disabled                 | false | true           | false      "
  })
  void testEvaluateBlockDefaultsToRemoteIsBlockingEnabled(
      Boolean block, boolean remoteBlocking, boolean shouldBlock) throws IOException {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "DENY");
    attributes.put("reason", "Nope");
    attributes.put("tags", singletonList("deny_everything"));
    attributes.put("is_blocking_enabled", remoteBlocking);
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));
    Options options = block == null ? DEFAULT : new Options().block(block);

    if (shouldBlock) {
      AIGuardAbortError error =
          assertThrows(AIGuardAbortError.class, () -> aiguard.evaluate(TOOL_CALL, options));
      assertEquals(DENY, error.getAction());
    } else {
      Evaluation eval = aiguard.evaluate(TOOL_CALL, options);
      assertEquals(DENY, eval.getAction());
    }
  }

  @Test
  void testEvaluateAppliesCapturedClientIpTagsToLocalRootSpan() throws IOException {
    RequestContext requestContext = mock(RequestContext.class);
    when(localRootSpan.getRequestContext()).thenReturn(requestContext);
    when(requestContext.getClientIpAddressData())
        .thenReturn(new ClientIpAddressData("4.4.4.4", "2.3.4.5"));
    when(localRootSpan.getTag(NETWORK_CLIENT_IP)).thenReturn(null);
    when(localRootSpan.getTag(HTTP_CLIENT_IP)).thenReturn(null);
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));

    aiguard.evaluate(TOOL_CALL, DEFAULT);

    verify(localRootSpan).setTag(NETWORK_CLIENT_IP, "4.4.4.4");
    verify(localRootSpan).setTag(HTTP_CLIENT_IP, "2.3.4.5");
  }

  @Test
  void testEvaluateDoesNotOverwriteExistingClientIpTags() throws IOException {
    RequestContext requestContext = mock(RequestContext.class);
    when(localRootSpan.getRequestContext()).thenReturn(requestContext);
    when(requestContext.getClientIpAddressData())
        .thenReturn(new ClientIpAddressData("4.4.4.4", "2.3.4.5"));
    when(localRootSpan.getTag(NETWORK_CLIENT_IP)).thenReturn("9.9.9.9");
    when(localRootSpan.getTag(HTTP_CLIENT_IP)).thenReturn("8.8.8.8");
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));

    aiguard.evaluate(TOOL_CALL, DEFAULT);

    verifyNoClientIpTags();
  }

  @Test
  void testEvaluateIsANoopForClientIpTagsWhenNoDataCaptured() throws IOException {
    RequestContext requestContext = mock(RequestContext.class);
    when(localRootSpan.getRequestContext()).thenReturn(requestContext);
    when(requestContext.getClientIpAddressData()).thenReturn(null);
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));

    aiguard.evaluate(TOOL_CALL, DEFAULT);

    verifyNoClientIpTags();
  }

  @Test
  void testEvaluateIsANoopForClientIpTagsWhenNoRequestContext() throws IOException {
    when(localRootSpan.getRequestContext()).thenReturn(null);
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));

    aiguard.evaluate(TOOL_CALL, DEFAULT);

    verifyNoClientIpTags();
  }

  @Test
  void testEvaluateCopiesAnomalyDetectionTagsFromLocalRootSpanToAiGuardSpan() throws IOException {
    when(localRootSpan.getTag(HTTP_CLIENT_IP)).thenReturn("1.2.3.4");
    when(localRootSpan.getTag(NETWORK_CLIENT_IP)).thenReturn("5.6.7.8");
    when(localRootSpan.getTag(HTTP_USER_AGENT)).thenReturn("curl/8.0");
    when(localRootSpan.getTag("usr.id")).thenReturn("u-123");
    when(localRootSpan.getTag("usr.session_id")).thenReturn("s-456");
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));

    aiguard.evaluate(TOOL_CALL, DEFAULT);

    verify(span).setTag("ai_guard.http.client_ip", "1.2.3.4");
    verify(span).setTag("ai_guard.network.client.ip", "5.6.7.8");
    verify(span).setTag("ai_guard.http.useragent", "curl/8.0");
    verify(span).setTag("ai_guard.usr.id", "u-123");
    verify(span).setTag("ai_guard.usr.session_id", "s-456");
  }

  @Test
  void testEvaluateSkipsMissingAnomalyDetectionTags() throws IOException {
    when(localRootSpan.getTag(HTTP_CLIENT_IP)).thenReturn("1.2.3.4");
    when(localRootSpan.getTag(NETWORK_CLIENT_IP)).thenReturn(null);
    when(localRootSpan.getTag(HTTP_USER_AGENT)).thenReturn(null);
    when(localRootSpan.getTag("usr.id")).thenReturn("u-123");
    when(localRootSpan.getTag("usr.session_id")).thenReturn(null);
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));

    aiguard.evaluate(TOOL_CALL, DEFAULT);

    verify(span).setTag("ai_guard.http.client_ip", "1.2.3.4");
    verify(span).setTag("ai_guard.usr.id", "u-123");
    verify(span, never()).setTag(eq("ai_guard.network.client.ip"), nullable(String.class));
    verify(span, never()).setTag(eq("ai_guard.http.useragent"), nullable(String.class));
    verify(span, never()).setTag(eq("ai_guard.usr.session_id"), nullable(String.class));
  }

  @Test
  void testEvaluateWithApiErrors() throws IOException {
    Map<String, Object> badRequest = new HashMap<>();
    // Moshi parses every JSON number as a double
    badRequest.put("status", 400D);
    badRequest.put("title", "Bad request");
    List<Map<String, Object>> errors = singletonList(badRequest);
    AIGuardInternal aiguard = mockClient(404, singletonMap("errors", errors));

    AIGuardClientError exception =
        assertThrows(AIGuardClientError.class, () -> aiguard.evaluate(TOOL_CALL, DEFAULT));

    assertEquals(errors, exception.getErrors());
    verify(span).addThrowable(any(AIGuardClientError.class));
    assertErrorTelemetry("bad_status");
  }

  @Test
  void testEvaluateWithInvalidJson() throws IOException {
    AIGuardInternal aiguard = mockClient(200, singletonMap("bad", "This is an invalid response"));

    assertThrows(AIGuardClientError.class, () -> aiguard.evaluate(TOOL_CALL, DEFAULT));

    verify(span).addThrowable(any(AIGuardClientError.class));
    assertErrorTelemetry("bad_response");
  }

  @Test
  void testEvaluateWithMissingAction() throws IOException {
    AIGuardInternal aiguard =
        mockClient(200, evaluation(singletonMap("reason", "I miss something")));

    assertThrows(AIGuardClientError.class, () -> aiguard.evaluate(TOOL_CALL, DEFAULT));

    verify(span).addThrowable(any(AIGuardClientError.class));
    assertErrorTelemetry("bad_response");
  }

  @Test
  void testEvaluateWithNonJsonResponse() throws IOException {
    AIGuardInternal aiguard = mockClient(200, "I am no JSON");

    assertThrows(AIGuardClientError.class, () -> aiguard.evaluate(TOOL_CALL, DEFAULT));

    verify(span).addThrowable(any(AIGuardClientError.class));
    assertErrorTelemetry("bad_response");
  }

  @Test
  void testEvaluateWithEmptyResponse() throws IOException {
    AIGuardInternal aiguard = mockClient(200, null);

    assertThrows(AIGuardClientError.class, () -> aiguard.evaluate(TOOL_CALL, DEFAULT));

    verify(span).addThrowable(any(AIGuardClientError.class));
    assertErrorTelemetry("bad_response");
  }

  @Test
  void testEvaluateWithATransportFailure() throws IOException {
    Call call = mock(Call.class);
    when(call.execute()).thenThrow(new IOException("connection reset"));
    OkHttpClient client = mock(OkHttpClient.class);
    when(client.newCall(any(Request.class))).thenReturn(call);
    AIGuardInternal aiguard = new AIGuardInternal(URL, HEADERS, client);

    assertThrows(AIGuardClientError.class, () -> aiguard.evaluate(TOOL_CALL, DEFAULT));

    verify(span).addThrowable(any(AIGuardClientError.class));
    // nothing about the response is known, so the failure keeps the default classification
    assertErrorTelemetry("client_error");
  }

  @Test
  void testMessageLengthTruncation() throws IOException {
    int maxMessages = Config.get().getAiGuardMaxMessagesLength();
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i <= maxMessages; i++) {
      messages.add(Message.message("user", "This is a prompt: " + i));
    }

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<Message> received = receivedMessages();
    assertEquals(maxMessages, received.size());
    assertTrue(received.size() < messages.size());
    assertTelemetry("truncated", "type:messages");
  }

  @Test
  void testMessageContentTruncation() throws IOException {
    int maxContent = Config.get().getAiGuardMaxContentSize();
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));
    Message message = Message.message("user", repeat('A', maxContent + 1));

    aiguard.evaluate(singletonList(message), DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<Message> received = receivedMessages();
    Message truncated = received.get(received.size() - 1);
    assertEquals(maxContent, truncated.getContent().length());
    assertTrue(truncated.getContent().length() < message.getContent().length());
    assertTelemetry("truncated", "type:content");
  }

  @TableTest({
    "scenario       | messages",
    "empty messages | []      ",
    "null messages  |         "
  })
  void testNoMessages(List<Message> messages) {
    AIGuardInternal aiguard = new AIGuardInternal(URL, HEADERS, mock(OkHttpClient.class));

    assertThrows(IllegalArgumentException.class, () -> aiguard.evaluate(messages, DEFAULT));
  }

  @Test
  void testEvaluateWithSdsFindings() throws IOException {
    List<Map<String, Object>> sdsFindings =
        asList(
            sdsFinding(
                "Credit Card Number",
                "credit_card",
                "4111111111111111",
                10D,
                26D,
                "messages[0].content[0].text"),
            sdsFinding(
                "Social Security Number",
                "ssn",
                "123-45-6789",
                30D,
                41D,
                "messages[1].tool_calls[0].function.arguments"));
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put("sds_findings", sdsFindings);
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    Evaluation result = aiguard.evaluate(PROMPT, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    assertEquals(sdsFindings, receivedMeta.get("sds"));
    assertEquals(sdsFindings, result.getSds());
  }

  @TableTest({
    "scenario       | sdsFindings",
    "null findings  |            ",
    "empty findings | []         "
  })
  void testEvaluateWithEmptySdsFindings(List<Object> sdsFindings) throws IOException {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put("sds_findings", sdsFindings);
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    Evaluation result = aiguard.evaluate(PROMPT, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    assertFalse(receivedMeta.containsKey("sds"));
    assertEquals(sdsFindings == null ? emptyList() : sdsFindings, result.getSds());
  }

  @Test
  void testEvaluateWithSdsFindingsInAbortError() throws IOException {
    List<Map<String, Object>> sdsFindings =
        singletonList(
            sdsFinding(
                "Credit Card Number",
                "credit_card",
                "4111111111111111",
                10D,
                26D,
                "messages[0].content[0].text"));
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ABORT");
    attributes.put("reason", "PII detected");
    attributes.put("tags", singletonList("pii"));
    attributes.put("sds_findings", sdsFindings);
    attributes.put("is_blocking_enabled", true);
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    AIGuardAbortError error =
        assertThrows(
            AIGuardAbortError.class, () -> aiguard.evaluate(PROMPT, new Options().block(true)));

    assertEquals(sdsFindings, error.getSds());
  }

  @Test
  void testEvaluateWithExtras() throws IOException {
    Map<String, Object> policyMatch = new HashMap<>();
    policyMatch.put("rule", "credit_card");
    policyMatch.put("categories", asList("pii", "financial"));
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put(
        "extra_tags",
        asList(entry("ai_guard.evaluate_id", "evaluation-123"), entry("custom_context.note", "")));
    attributes.put(
        "extra_metastruct",
        asList(
            entry("policy_match", policyMatch),
            entry("flag", false),
            entry("empty", emptyList()),
            entry("policy_version", "v3")));
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    Evaluation result = aiguard.evaluate(PROMPT, DEFAULT);

    assertEquals(ALLOW, result.getAction());
    verify(span).setTag("ai_guard.evaluate_id", (Object) "evaluation-123");
    verify(span).setTag("custom_context.note", (Object) "");
    verify(span, never()).setTag(eq("ai_guard.ai_guard.evaluate_id"), (Object) anyString());
    verify(span, never()).setTag(eq("policy_match"), (Object) anyString());
    assertEquals(policyMatch, receivedMeta.get("policy_match"));
    assertEquals(false, receivedMeta.get("flag"));
    assertEquals(emptyList(), receivedMeta.get("empty"));
    assertEquals("v3", receivedMeta.get("policy_version"));
    assertFalse(receivedMeta.containsKey("ai_guard.evaluate_id"));
    assertFalse(receivedMeta.containsKey("custom_context.note"));
  }

  @Test
  void testEvaluateWithoutExtras() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "It is fine"));

    aiguard.evaluate(PROMPT, DEFAULT);

    verify(span, never()).setTag(eq("ai_guard.evaluate_id"), (Object) anyString());
    assertEquals(singletonList("messages"), new ArrayList<>(receivedMeta.keySet()));
  }

  @TableTest({
    "scenario                      | field           ",
    "extra_tags is an object       | extra_tags      ",
    "extra_metastruct is an object | extra_metastruct"
  })
  void testEvaluateWithExtrasNotAList(String field) throws IOException {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put("extra_tags", singletonList(entry("ai_guard.evaluate_id", "evaluation-123")));
    attributes.put("extra_metastruct", singletonList(entry("flag", true)));
    attributes.put(field, singletonMap("ai_guard.policy", "example"));
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    Evaluation result = aiguard.evaluate(PROMPT, DEFAULT);

    assertEquals(ALLOW, result.getAction());
    verify(span, never()).addThrowable(any());
    verify(span, never()).setTag(eq("ai_guard.policy"), (Object) anyString());
    assertFalse(receivedMeta.containsKey("ai_guard.policy"));
    if (field.equals("extra_tags")) {
      verify(span, never()).setTag(eq("ai_guard.evaluate_id"), (Object) anyString());
      assertEquals(true, receivedMeta.get("flag"));
    } else {
      verify(span).setTag("ai_guard.evaluate_id", (Object) "evaluation-123");
      assertFalse(receivedMeta.containsKey("flag"));
    }
  }

  @Test
  void testEvaluateWithMalformedExtras() throws IOException {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put(
        "extra_tags",
        asList(
            entry("ai_guard.evaluate_id", "evaluation-123"),
            null,
            "not an object",
            singletonMap("value", "missing key"),
            entry("", "empty key"),
            entry(1, "numeric key"),
            entry("bad.number", 42),
            entry("bad.object", emptyMap()),
            entry("bad.boolean", true),
            entry("bad.nil", null),
            entry("custom_context.after_invalid", "kept")));
    attributes.put(
        "extra_metastruct",
        asList(
            entry("flag", false),
            "not an object",
            singletonMap("value", "missing key"),
            entry("", "empty key"),
            entry("bad.nil", null),
            entry("after_invalid", true)));
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    Evaluation result = aiguard.evaluate(PROMPT, DEFAULT);

    assertEquals(ALLOW, result.getAction());
    verify(span, never()).addThrowable(any());
    verify(span).setTag("ai_guard.evaluate_id", (Object) "evaluation-123");
    verify(span).setTag("custom_context.after_invalid", (Object) "kept");
    for (String key : asList("", "1", "bad.number", "bad.object", "bad.boolean", "bad.nil")) {
      verify(span, never()).setTag(eq(key), nullable(Object.class));
    }
    assertEquals(false, receivedMeta.get("flag"));
    assertEquals(true, receivedMeta.get("after_invalid"));
    assertFalse(receivedMeta.containsKey(""));
    assertFalse(receivedMeta.containsKey("bad.nil"));
  }

  @Test
  void testEvaluateWithExtrasIsPassThrough() throws IOException {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put(
        "extra_tags",
        asList(
            entry("_dd.custom", "value"),
            entry("custom.tag", "first"),
            entry("custom.tag", "second")));
    attributes.put("extra_metastruct", asList(entry("key", "first"), entry("key", "second")));
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    aiguard.evaluate(PROMPT, DEFAULT);

    // keys are neither prefixed nor filtered by namespace, and the last duplicate wins
    verify(span).setTag("_dd.custom", (Object) "value");
    verify(span).setTag("custom.tag", (Object) "second");
    verify(span, never()).setTag("custom.tag", (Object) "first");
    assertEquals("second", receivedMeta.get("key"));
  }

  @Test
  void testEvaluateWithReservedExtras() throws IOException {
    List<Object> extraTags = new ArrayList<>();
    extraTags.add(entry("ai_guard.evaluate_id", "evaluation-123"));
    for (String key :
        asList(
            Tags.AI_GUARD_KEEP,
            Tags.AI_GUARD_EVENT,
            "ai_guard." + HTTP_CLIENT_IP,
            "ai_guard." + NETWORK_CLIENT_IP,
            "ai_guard." + HTTP_USER_AGENT,
            "ai_guard.usr.id",
            "ai_guard.usr.session_id",
            "manual.keep",
            "manual.drop",
            "sampling.priority",
            "service",
            "resource.name",
            "span.type",
            "error")) {
      extraTags.add(entry(key, "forged"));
    }
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put("extra_tags", extraTags);
    attributes.put("extra_metastruct", singletonList(entry("messages", "forged")));
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    aiguard.evaluate(PROMPT, DEFAULT);

    verify(span).setTag("ai_guard.evaluate_id", (Object) "evaluation-123");
    verify(span, never()).setTag(anyString(), (Object) eq("forged"));
    // the tracer always reports the evaluated conversation
    assertEquals(PROMPT.size(), receivedMessages().size());
  }

  @Test
  void testEvaluateWithExtrasCollidingWithEvaluation() throws IOException {
    List<String> categories = singletonList("jailbreak");
    Map<String, Double> tagProbs = singletonMap("jailbreak", 0.9D);
    List<Map<String, Object>> sdsFindings =
        singletonList(
            sdsFinding(
                "Credit Card Number",
                "credit_card",
                "4111111111111111",
                10D,
                26D,
                "messages[0].content[0].text"));
    List<Object> extraTags = new ArrayList<>();
    extraTags.add(entry("ai_guard.evaluate_id", "evaluation-123"));
    for (String key :
        asList(
            ACTION_TAG,
            REASON_TAG,
            TARGET_TAG,
            TOOL_TAG,
            AIGuardInternal.BLOCKED_TAG,
            AIGuardInternal.REDACTED_TAG)) {
      extraTags.add(entry(key, "forged"));
    }
    List<Object> extraMetaStruct = new ArrayList<>();
    extraMetaStruct.add(entry("policy_version", "v3"));
    for (String key : asList("messages", "attack_categories", "tag_probs", "sds")) {
      extraMetaStruct.add(entry(key, "forged"));
    }
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "DENY");
    attributes.put("reason", "Nope");
    attributes.put("is_blocking_enabled", true);
    attributes.put("tags", categories);
    attributes.put("tag_probs", tagProbs);
    attributes.put("sds_findings", sdsFindings);
    attributes.put("extra_tags", extraTags);
    attributes.put("extra_metastruct", extraMetaStruct);
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    assertThrows(
        AIGuardAbortError.class, () -> aiguard.evaluate(PROMPT, new Options().block(true)));

    // extras add to the evaluation but never overwrite what the tracer recorded from it
    verify(span).setTag("ai_guard.evaluate_id", (Object) "evaluation-123");
    verify(span, never()).setTag(anyString(), (Object) eq("forged"));
    verify(span).setTag(ACTION_TAG, DENY);
    verify(span).setTag(AIGuardInternal.BLOCKED_TAG, true);
    assertEquals("v3", receivedMeta.get("policy_version"));
    assertEquals(categories, receivedMeta.get("attack_categories"));
    assertEquals(tagProbs, receivedMeta.get("tag_probs"));
    assertEquals(sdsFindings, receivedMeta.get("sds"));
    assertEquals(PROMPT.size(), receivedMessages().size());
  }

  @Test
  @WithConfig(key = "trace.split-by-tags", value = "custom.service_tag,http.useragent")
  void testEvaluateWithSplitByExtras() throws IOException {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "ALLOW");
    attributes.put("reason", "It is fine");
    attributes.put(
        "extra_tags",
        asList(
            entry("ai_guard.evaluate_id", "evaluation-123"),
            entry("custom.service_tag", "forged"),
            entry("http.useragent", "forged"),
            // OpenTelemetry name of http.useragent
            entry("user_agent.original", "forged")));
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    aiguard.evaluate(PROMPT, DEFAULT);

    // split-by tags would rename the service instead of being stored on the span
    verify(span).setTag("ai_guard.evaluate_id", (Object) "evaluation-123");
    verify(span, never()).setTag(anyString(), (Object) eq("forged"));
  }

  @Test
  void testEvaluateWithExtrasWhenBlocking() throws IOException {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", "DENY");
    attributes.put("reason", "Nope");
    attributes.put("is_blocking_enabled", true);
    attributes.put("extra_tags", singletonList(entry("ai_guard.evaluate_id", "evaluation-123")));
    attributes.put("extra_metastruct", singletonList(entry("policy_version", "v3")));
    AIGuardInternal aiguard = mockClient(200, evaluation(attributes));

    assertThrows(
        AIGuardAbortError.class, () -> aiguard.evaluate(PROMPT, new Options().block(true)));

    verify(span).setTag("ai_guard.evaluate_id", (Object) "evaluation-123");
    verify(span).setTag(AIGuardInternal.BLOCKED_TAG, true);
    assertEquals("v3", receivedMeta.get("policy_version"));
  }

  @Test
  void testMissingToolName() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Just do it"));

    aiguard.evaluate(singletonList(Message.tool("call_1", "Content")), DEFAULT);

    verify(span).setTag(TARGET_TAG, "tool");
    verify(span, never()).setTag(eq(TOOL_TAG), nullable(String.class));
  }

  @Test
  void mapRequiresEvenNumberOfParams() {
    assertThrows(IllegalArgumentException.class, () -> AIGuardInternal.mapOf("1", "2", "3"));
  }

  @Test
  void testMessageImmutability() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Just do it"));
    List<Message> messages =
        new ArrayList<>(
            singletonList(
                new Message(
                    "assistant",
                    (String) null,
                    new ArrayList<>(
                        singletonList(
                            ToolCall.toolCall(
                                "call_1", "execute_shell", "{\"cmd\": \"ls -lah\"}"))),
                    null)));
    doAnswer(
            invocation -> {
              // modify the messages before serialization
              messages
                  .get(0)
                  .getToolCalls()
                  .add(ToolCall.toolCall("call_2", "execute_shell", "{\"cmd\": \"rm -rf\"}"));
              messages.add(Message.tool("call_1", "dir1, dir2, dir3"));
              return null;
            })
        .when(span)
        .finish();

    aiguard.evaluate(messages, DEFAULT);

    verify(span).finish();
    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any(Map.class));
    List<Message> metaStructMessages = receivedMessages();
    assertNotEquals(messages.size(), metaStructMessages.size());
    assertEquals(1, metaStructMessages.size());
    assertNotEquals(
        messages.get(0).getToolCalls().size(), metaStructMessages.get(0).getToolCalls().size());
    assertEquals(1, metaStructMessages.get(0).getToolCalls().size());
  }

  @Test
  void testJsonSerializationWithTextContentParts() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Good"));
    List<Message> messages =
        singletonList(Message.message("user", singletonList(ContentPart.text("Hello world"))));

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<Message> receivedMessages = receivedMessages();
    assertEquals(1, receivedMessages.size());
    List<ContentPart> contentParts = receivedMessages.get(0).getContentParts();
    assertEquals(1, contentParts.size());
    assertEquals(TEXT, contentParts.get(0).getType());
    assertEquals("Hello world", contentParts.get(0).getText());
  }

  @Test
  void testJsonSerializationWithImageUrlContentParts() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Good"));
    List<Message> messages =
        singletonList(
            Message.message(
                "user", singletonList(ContentPart.imageUrl("https://example.com/image.jpg"))));

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<Message> receivedMessages = receivedMessages();
    assertEquals(1, receivedMessages.size());
    List<ContentPart> contentParts = receivedMessages.get(0).getContentParts();
    assertEquals(1, contentParts.size());
    assertEquals(IMAGE_URL, contentParts.get(0).getType());
    assertEquals("https://example.com/image.jpg", contentParts.get(0).getImageUrl().getUrl());
  }

  @Test
  void testJsonSerializationWithMixedContentParts() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Good"));
    List<Message> messages =
        singletonList(
            Message.message(
                "user",
                asList(
                    ContentPart.text("Describe this image:"),
                    ContentPart.imageUrl("https://example.com/image.jpg"),
                    ContentPart.text("What do you see?"))));

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<Message> receivedMessages = receivedMessages();
    assertEquals(1, receivedMessages.size());
    List<ContentPart> contentParts = receivedMessages.get(0).getContentParts();
    assertEquals(3, contentParts.size());
    assertEquals(TEXT, contentParts.get(0).getType());
    assertEquals("Describe this image:", contentParts.get(0).getText());
    assertEquals(IMAGE_URL, contentParts.get(1).getType());
    assertEquals("https://example.com/image.jpg", contentParts.get(1).getImageUrl().getUrl());
    assertEquals(TEXT, contentParts.get(2).getType());
    assertEquals("What do you see?", contentParts.get(2).getText());
  }

  @Test
  void testContentPartsOrderIsPreserved() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Good"));
    List<ContentPart> parts = new ArrayList<>();
    for (int i = 0; i <= 4; i++) {
      parts.add(
          i % 2 == 0
              ? ContentPart.text("Text " + i)
              : ContentPart.imageUrl("https://example.com/image" + i + ".jpg"));
    }
    List<Message> messages = singletonList(Message.message("user", parts));

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<ContentPart> contentParts = receivedMessages().get(0).getContentParts();
    assertEquals(5, contentParts.size());
    for (int i = 0; i <= 4; i++) {
      if (i % 2 == 0) {
        assertEquals(TEXT, contentParts.get(i).getType());
        assertEquals("Text " + i, contentParts.get(i).getText());
      } else {
        assertEquals(IMAGE_URL, contentParts.get(i).getType());
        assertEquals(
            "https://example.com/image" + i + ".jpg", contentParts.get(i).getImageUrl().getUrl());
      }
    }
  }

  @Test
  void testContentPartTextTruncation() throws IOException {
    int maxContent = Config.get().getAiGuardMaxContentSize();
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Good"));
    String longText = repeat('A', maxContent + 1);
    List<Message> messages =
        singletonList(
            Message.message(
                "user", asList(ContentPart.text(longText), ContentPart.text("Short text"))));

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<ContentPart> contentParts = receivedMessages().get(0).getContentParts();
    assertEquals(2, contentParts.size());
    assertEquals(maxContent, contentParts.get(0).getText().length());
    assertTrue(contentParts.get(0).getText().length() < longText.length());
    assertEquals("Short text", contentParts.get(1).getText());
    assertTelemetry("truncated", "type:content");
  }

  @Test
  void testContentPartImageUrlNotTruncatedEvenWithLongDataUri() throws IOException {
    int maxContent = Config.get().getAiGuardMaxContentSize();
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Good"));
    // Create a very long data URI (longer than max content size)
    String longDataUri = "data:image/png;base64," + repeat('A', maxContent + 1001);
    List<Message> messages =
        singletonList(
            Message.message(
                "user", asList(ContentPart.text("Image:"), ContentPart.imageUrl(longDataUri))));

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<ContentPart> contentParts = receivedMessages().get(0).getContentParts();
    assertEquals(2, contentParts.size());
    assertEquals(IMAGE_URL, contentParts.get(1).getType());
    // Image URL should NOT be truncated
    assertEquals(longDataUri, contentParts.get(1).getImageUrl().getUrl());
    assertTrue(contentParts.get(1).getImageUrl().getUrl().length() > maxContent);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testAdapterSerializesContentPartsArguments")
  void testAdapterSerializesContentParts(String scenario, Message message, String expected)
      throws JSONException {
    JsonAdapter<Message> adapter =
        new Moshi.Builder()
            .add(new AIGuardInternal.AIGuardFactory())
            .build()
            .adapter(Message.class);

    // STRICT enforces array element ordering (object key order is always ignored), so a regression
    // that serialized the content parts out of sequence would be caught here
    JSONAssert.assertEquals(expected, adapter.toJson(message), JSONCompareMode.STRICT);
  }

  static Stream<Arguments> testAdapterSerializesContentPartsArguments() {
    return Stream.of(
        arguments(
            "empty content parts",
            Message.message("user", new ArrayList<>()),
            "{\"role\": \"user\", \"content\": []}"),
        arguments(
            "text content part",
            Message.message("user", singletonList(ContentPart.text("Hello world"))),
            "{\"role\": \"user\", \"content\": [{\"type\": \"text\", \"text\": \"Hello world\"}]}"),
        arguments(
            "image_url content part",
            Message.message(
                "user", singletonList(ContentPart.imageUrl("https://example.com/image.jpg"))),
            "{\"role\": \"user\", \"content\": [{\"type\": \"image_url\", \"image_url\": {\"url\": \"https://example.com/image.jpg\"}}]}"),
        arguments(
            "mixed content parts",
            Message.message(
                "user",
                asList(
                    ContentPart.text("Describe this image:"),
                    ContentPart.imageUrl("https://example.com/image.jpg"),
                    ContentPart.text("What do you see?"))),
            "{\"role\": \"user\", \"content\": [{\"type\": \"text\", \"text\": \"Describe this image:\"}, {\"type\": \"image_url\", \"image_url\": {\"url\": \"https://example.com/image.jpg\"}}, {\"type\": \"text\", \"text\": \"What do you see?\"}]}"));
  }

  @Test
  void testBackwardCompatibilityWithStringContent() throws IOException {
    AIGuardInternal aiguard = mockClient(200, evaluation("ALLOW", "Good"));
    List<Message> messages = singletonList(Message.message("user", "Hello world"));

    aiguard.evaluate(messages, DEFAULT);

    verify(span).setMetaStruct(eq(META_STRUCT_TAG), any());
    List<Message> receivedMessages = receivedMessages();
    assertEquals(1, receivedMessages.size());
    assertEquals("Hello world", receivedMessages.get(0).getContent());
    assertNull(receivedMessages.get(0).getContentParts());
  }

  private void verifyNoClientIpTags() {
    verify(localRootSpan, never()).setTag(eq(NETWORK_CLIENT_IP), nullable(String.class));
    verify(localRootSpan, never()).setTag(eq(HTTP_CLIENT_IP), nullable(String.class));
  }

  @SuppressWarnings("unchecked")
  private List<Message> receivedMessages() {
    return (List<Message>) receivedMeta.get("messages");
  }

  private AIGuardInternal mockClient(int status, Object response) throws IOException {
    Call call = mock(Call.class);
    when(call.execute()).thenAnswer(invocation -> mockResponse(request, status, response));
    OkHttpClient client = mock(OkHttpClient.class);
    when(client.newCall(any(Request.class)))
        .thenAnswer(invocation -> {
          request = invocation.getArgument(0);
          return call;
        });
    return new AIGuardInternal(URL, HEADERS, client);
  }

  private static Map<String, Object> evaluation(String action, String reason) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("action", action);
    attributes.put("reason", reason);
    return evaluation(attributes);
  }

  private static Map<String, Object> evaluation(Map<String, Object> attributes) {
    return singletonMap("data", singletonMap("attributes", attributes));
  }

  /** A {@code {key, value}} entry of {@code extra_tags} or {@code extra_metastruct}. */
  private static Map<String, Object> entry(Object key, Object value) {
    Map<String, Object> entry = new HashMap<>();
    entry.put("key", key);
    entry.put("value", value);
    return entry;
  }

  private static Map<String, Object> sdsFinding(
      String ruleDisplayName,
      String ruleTag,
      String matchedText,
      double startIndex,
      double endIndexExclusive,
      String path) {
    Map<String, Object> location = new HashMap<>();
    // Moshi parses every JSON number as a double
    location.put("start_index", startIndex);
    location.put("end_index_exclusive", endIndexExclusive);
    location.put("path", path);
    Map<String, Object> finding = new HashMap<>();
    finding.put("rule_display_name", ruleDisplayName);
    finding.put("rule_tag", ruleTag);
    finding.put("category", "pii");
    finding.put("matched_text", matchedText);
    finding.put("location", location);
    return finding;
  }

  private static void assertTelemetry(String metric, String... tags) {
    assertTelemetry(drainAIGuard(), metric, tags);
  }

  /**
   * Asserts one ai_guard metric out of an already drained batch. Every metric in the namespace
   * carries the call path, constant while a direct SDK call is the only way to reach an evaluation.
   */
  private static void assertTelemetry(List<Metric> metrics, String metric, String... tags) {
    List<String> expected = new ArrayList<>(asList(tags));
    expected.add("source:sdk");
    expected.add("integration:none");
    List<Metric> filtered =
        metrics.stream()
            .filter(it ->
                "ai_guard".equals(it.namespace)
                    && metric.equals(it.metricName)
                    && expected.equals(it.tags))
            .collect(toList());
    assertEquals(1, filtered.size(), metrics::toString);
    assertEquals(1, filtered.stream().mapToLong(it -> it.value.longValue()).sum());
  }

  /**
   * A failed evaluation is reported twice: as a failed request, which keeps the request count
   * complete, and under ai_guard.error with the type that classifies the failure.
   */
  private static void assertErrorTelemetry(String type) {
    List<Metric> metrics = drainAIGuard();
    assertTelemetry(metrics, "requests", "error:true");
    assertTelemetry(metrics, "error", "type:" + type);
  }

  private static List<Metric> drainAIGuard() {
    WafMetricCollector.get().prepareMetrics();
    return new ArrayList<>(WafMetricCollector.get().drain());
  }

  private static void assertMeta(Map<String, Object> meta, TestSuite suite)
      throws JsonProcessingException, JSONException {
    if (!suite.tags.isEmpty()) {
      assertEquals(suite.tags, meta.get("attack_categories"));
    }
    if (!suite.tagProbabilities.isEmpty()) {
      assertEquals(suite.tagProbabilities, meta.get("tag_probs"));
    }
    String receivedMessages = snakeCaseJson(meta.get("messages"));
    String expectedMessages = snakeCaseJson(suite.messages);
    JSONAssert.assertEquals(expectedMessages, receivedMessages, JSONCompareMode.NON_EXTENSIBLE);
  }

  private static void assertRequest(Request request, List<Message> messages)
      throws IOException, JSONException {
    assertEquals(URL, request.url());
    assertEquals("POST", request.method());
    HEADERS.forEach((name, value) -> assertEquals(value, request.header(name)));
    assertTrue(request.body().contentType().toString().contains("application/json"));
    String receivedBody = readRequestBody(request.body());
    Map<String, Object> meta = new HashMap<>();
    meta.put("service", "ai_guard_test");
    meta.put("env", "test");
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("messages", messages);
    attributes.put("meta", meta);
    String expectedBody = snakeCaseJson(evaluation(attributes));
    JSONAssert.assertEquals(expectedBody, receivedBody, JSONCompareMode.NON_EXTENSIBLE);
  }

  private static String snakeCaseJson(Object value) throws JsonProcessingException {
    return MAPPER.writeValueAsString(value);
  }

  private static String readRequestBody(RequestBody body) throws IOException {
    Buffer buffer = new Buffer();
    body.writeTo(buffer);
    return buffer.readUtf8();
  }

  private static Response mockResponse(Request request, int status, Object body) {
    return new Response.Builder()
        .protocol(Protocol.HTTP_1_1)
        .message("ok")
        .request(request)
        .code(status)
        .body(
            body == null
                ? null
                : ResponseBody.create(
                    MediaType.parse("application/json"), MOSHI.adapter(Object.class).toJson(body)))
        .build();
  }

  private static String repeat(char character, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, character);
    return new String(chars);
  }

  private static List<Message> concat(List<Message> messages, Message... others) {
    List<Message> result = new ArrayList<>(messages);
    result.addAll(asList(others));
    return result;
  }

  static class TestSuite {
    private final Action action;
    private final String reason;
    private final List<String> tags;
    private final Map<String, Double> tagProbabilities;
    private final boolean blocking;
    private final String description;
    private final String target;
    private final List<Message> messages;

    TestSuite(
        Verdict verdict,
        boolean blocking,
        String description,
        String target,
        List<Message> messages) {
      this.action = verdict.action;
      this.reason = verdict.reason;
      this.tags = new ArrayList<>(verdict.tagProbabilities.keySet());
      this.tagProbabilities = verdict.tagProbabilities;
      this.blocking = blocking;
      this.description = description;
      this.target = target;
      this.messages = messages;
    }

    @Override
    public String toString() {
      return "TestSuite{"
          + "description='"
          + description
          + '\''
          + ", action="
          + action
          + ", reason='"
          + reason
          + '\''
          + ", blocking="
          + blocking
          + ", target='"
          + target
          + '\''
          + ", messages="
          + messages.stream().map(Message::getContent).collect(toList())
          + '\''
          + ", tags="
          + tags
          + '}';
    }

    /** The response of the AI Guard service for a test suite. */
    static class Verdict {
      private final Action action;
      private final String reason;
      private final Map<String, Double> tagProbabilities;

      Verdict(Action action, String reason, Map<String, Double> tagProbabilities) {
        this.action = action;
        this.reason = reason;
        this.tagProbabilities = tagProbabilities;
      }
    }
  }
}
