package com.datadog.aiguard;

import static datadog.trace.api.aiguard.AIGuard.Action.ABORT;
import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW;
import static datadog.trace.api.aiguard.AIGuard.Action.DENY;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.junit.utils.config.WithConfigExtension.removeEnvConfig;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.squareup.moshi.Moshi;
import datadog.common.version.VersionInfo;
import datadog.trace.api.Config;
import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import okio.BufferedSink;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.tabletest.junit.TableTest;

class AIGuardInternalTests extends DDJavaSpecification {

  private static final HttpUrl URL =
      HttpUrl.parse("https://app.datadoghq.com/api/v2/ai-guard/evaluate");

  private static final Map<String, String> HEADERS = buildHeaders();

  private static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get();

  private static final Moshi MOSHI = new Moshi.Builder().build();

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .setDefaultPropertyInclusion(
              JsonInclude.Value.construct(
                  JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));

  private static final List<AIGuard.Message> TOOL_CALL =
      Arrays.asList(
          AIGuard.Message.message("system", "You are a beautiful AI assistant"),
          AIGuard.Message.message("user", "What is 2 + 2"),
          AIGuard.Message.assistant(
              AIGuard.ToolCall.toolCall(
                  "call_1", "calc", "{ \"operator\": \"+\", \"args\": [2, 2] }")));

  private static final List<AIGuard.Message> TOOL_OUTPUT =
      appendMessage(TOOL_CALL, AIGuard.Message.tool("call_1", "5"));

  private static final List<AIGuard.Message> PROMPT =
      appendMessages(
          TOOL_OUTPUT,
          AIGuard.Message.message("assistant", "2 + 2 is 5"),
          AIGuard.Message.message("user", ""));

  private AgentSpan span;
  private AgentSpan localRootSpan;

  private static Map<String, String> buildHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("DD-API-KEY", "api");
    headers.put("DD-APPLICATION-KEY", "app");
    headers.put("DD-AI-GUARD-VERSION", VersionInfo.VERSION);
    headers.put("DD-AI-GUARD-SOURCE", "SDK");
    headers.put("DD-AI-GUARD-LANGUAGE", "jvm");
    return headers;
  }

  private static List<AIGuard.Message> appendMessage(
      List<AIGuard.Message> base, AIGuard.Message extra) {
    List<AIGuard.Message> result = new ArrayList<>(base);
    result.add(extra);
    return result;
  }

  private static List<AIGuard.Message> appendMessages(
      List<AIGuard.Message> base, AIGuard.Message... extra) {
    List<AIGuard.Message> result = new ArrayList<>(base);
    result.addAll(Arrays.asList(extra));
    return result;
  }

  @BeforeEach
  void setup() {
    injectEnvConfig("SERVICE", "ai_guard_test");
    injectEnvConfig("ENV", "test");

    span = mock(AgentSpan.class);
    localRootSpan = mock(AgentSpan.class);
    when(span.getLocalRootSpan()).thenReturn(localRootSpan);

    AgentTracer.SpanBuilder builder = mock(AgentTracer.SpanBuilder.class);
    when(builder.start()).thenReturn(span);

    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.buildSpan(anyString(), any(CharSequence.class))).thenReturn(builder);
    AgentTracer.forceRegister(tracer);

    WafMetricCollector wafMetricCollector = WafMetricCollector.get();
    wafMetricCollector.prepareMetrics();
    wafMetricCollector.drain();
  }

  @AfterEach
  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER);
    AIGuardInternal.uninstall();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @TableTest({
    "scenario     | apiKey | appKey",
    "missing app  | apiKey |       ",
    "empty app    | apiKey | ''    ",
    "missing api  |        | appKey",
    "empty api    | ''     | appKey",
    "both missing |        |       ",
    "both empty   | ''     | ''    "
  })
  void testMissingApiAppKeys(String scenario, String apiKey, String appKey) {
    if (apiKey != null) {
      injectEnvConfig("API_KEY", apiKey);
    }
    if (appKey != null) {
      injectEnvConfig("APP_KEY", appKey);
    }

    assertThrows(AIGuardInternal.BadConfigurationException.class, AIGuardInternal::install);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @TableTest({
    "scenario          | endpoint       | site            | expected                                            ",
    "explicit endpoint | 'https://test' |                 | 'https://test/evaluate'                             ",
    "no site           |                |                 | 'https://app.datadoghq.com/api/v2/ai-guard/evaluate'",
    "default site      |                | 'datadoghq.com' | 'https://app.datadoghq.com/api/v2/ai-guard/evaluate'",
    "staging site      |                | 'datad0g.com'   | 'https://app.datad0g.com/api/v2/ai-guard/evaluate'  "
  })
  void testEndpointDiscovery(String scenario, String endpoint, String site, String expected)
      throws Exception {
    injectEnvConfig("API_KEY", "api");
    injectEnvConfig("APP_KEY", "app");
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

    Field evaluator = AIGuard.class.getDeclaredField("EVALUATOR");
    evaluator.setAccessible(true);
    AIGuardInternal internal = (AIGuardInternal) evaluator.get(null);
    Field urlField = AIGuardInternal.class.getDeclaredField("url");
    urlField.setAccessible(true);
    HttpUrl url = (HttpUrl) urlField.get(internal);
    assertEquals(expected, url.toString());
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testEvaluateArguments")
  @SuppressWarnings("unchecked")
  void testEvaluate(TestSuite suite) throws Exception {
    boolean throwAbortError = suite.blocking && suite.action != ALLOW;

    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("action", suite.action);
    attributes.put("reason", suite.reason);
    attributes.put("tags", suite.tags != null ? suite.tags : emptyList());
    attributes.put(
        "tag_probs", suite.tagProbabilities != null ? suite.tagProbabilities : emptyMap());
    attributes.put("is_blocking_enabled", suite.blocking);

    RequestHolder holder = new RequestHolder();
    AIGuardInternal aiguard =
        mockClient(holder, 200, mapOf("data", mapOf("attributes", attributes)));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);

    AIGuard.Evaluation eval = null;
    Throwable error = null;
    try {
      eval = aiguard.evaluate(suite.messages, new AIGuard.Options().block(suite.blocking));
    } catch (Throwable e) {
      error = e;
    }

    verify(span).setTag(AIGuardInternal.TARGET_TAG, suite.target);
    verify(localRootSpan).setTag(Tags.AI_GUARD_KEEP, true);
    verify(localRootSpan).setTag(AIGuardInternal.EVENT_TAG, true);
    if ("tool".equals(suite.target)) {
      verify(span).setTag(AIGuardInternal.TOOL_TAG, "calc");
    }
    verify(span).setTag(AIGuardInternal.ACTION_TAG, suite.action);
    verify(span).setTag(AIGuardInternal.REASON_TAG, suite.reason);
    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    if (throwAbortError) {
      verify(span).addThrowable(any(AIGuard.AIGuardAbortError.class));
    }

    Map<String, Object> receivedMeta = metaCaptor.getValue();
    assertMeta(receivedMeta, suite);
    assertRequest(holder.request, suite.messages);

    if (throwAbortError) {
      assertTrue(error instanceof AIGuard.AIGuardAbortError);
      AIGuard.AIGuardAbortError abort = (AIGuard.AIGuardAbortError) error;
      assertEquals(suite.action, abort.getAction());
      assertEquals(suite.reason, abort.getReason());
      assertEquals(suite.tags, abort.getTags());
      assertEquals(suite.tagProbabilities, abort.getTagProbabilities());
      assertEquals(emptyList(), abort.getSds());
    } else {
      assertNull(error);
      assertEquals(suite.action, eval.getAction());
      assertEquals(suite.reason, eval.getReason());
      assertEquals(suite.tags, eval.getTags());
      assertEquals(suite.tagProbabilities, eval.getTagProbabilities());
      assertEquals(emptyList(), eval.getSds());
    }
    assertTelemetry(
        "ai_guard.requests", "action:" + suite.action, "block:" + throwAbortError, "error:false");
  }

  static Stream<Arguments> testEvaluateArguments() {
    return TestSuite.build().stream().map(s -> arguments(s));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testEvaluateBlockDefaultsArguments")
  void testEvaluateBlockDefaultsToRemoteIsBlockingEnabled(
      String scenario, AIGuard.Options options, boolean remoteBlocking, boolean shouldBlock) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("action", "DENY");
    attributes.put("reason", "Nope");
    attributes.put("tags", singletonList("deny_everything"));
    attributes.put("is_blocking_enabled", remoteBlocking);

    RequestHolder holder = new RequestHolder();
    AIGuardInternal aiguard =
        mockClient(holder, 200, mapOf("data", mapOf("attributes", attributes)));

    AIGuard.Evaluation eval = null;
    Throwable error = null;
    try {
      eval = aiguard.evaluate(TOOL_CALL, options);
    } catch (Throwable e) {
      error = e;
    }

    if (shouldBlock) {
      assertTrue(error instanceof AIGuard.AIGuardAbortError);
      assertEquals(DENY, ((AIGuard.AIGuardAbortError) error).getAction());
    } else {
      assertNull(error);
      assertEquals(DENY, eval.getAction());
    }
  }

  static Stream<Arguments> testEvaluateBlockDefaultsArguments() {
    return Stream.of(
        arguments("default options + remote blocking", AIGuard.Options.DEFAULT, true, true),
        arguments("default options + no remote blocking", AIGuard.Options.DEFAULT, false, false),
        arguments(
            "explicit no block + remote blocking",
            new AIGuard.Options().block(false),
            true,
            false));
  }

  @Test
  void testEvaluateAppliesCapturedClientIpTagsToLocalRootSpan() {
    RequestContext requestContext = mock(RequestContext.class);
    when(localRootSpan.getRequestContext()).thenReturn(requestContext);
    when(requestContext.getClientIpAddressData())
        .thenReturn(new ClientIpAddressData("4.4.4.4", "2.3.4.5"));
    when(localRootSpan.getTag(Tags.NETWORK_CLIENT_IP)).thenReturn(null);
    when(localRootSpan.getTag(Tags.HTTP_CLIENT_IP)).thenReturn(null);

    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "It is fine"))));

    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT);

    verify(localRootSpan).setTag(Tags.NETWORK_CLIENT_IP, "4.4.4.4");
    verify(localRootSpan).setTag(Tags.HTTP_CLIENT_IP, "2.3.4.5");
  }

  @Test
  void testEvaluateDoesNotOverwriteExistingClientIpTags() {
    RequestContext requestContext = mock(RequestContext.class);
    when(localRootSpan.getRequestContext()).thenReturn(requestContext);
    when(requestContext.getClientIpAddressData())
        .thenReturn(new ClientIpAddressData("4.4.4.4", "2.3.4.5"));
    when(localRootSpan.getTag(Tags.NETWORK_CLIENT_IP)).thenReturn("9.9.9.9");
    when(localRootSpan.getTag(Tags.HTTP_CLIENT_IP)).thenReturn("8.8.8.8");

    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "It is fine"))));

    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT);

    verify(localRootSpan, never()).setTag(eq(Tags.NETWORK_CLIENT_IP), any(String.class));
    verify(localRootSpan, never()).setTag(eq(Tags.HTTP_CLIENT_IP), any(String.class));
  }

  @Test
  void testEvaluateIsANoopForClientIpTagsWhenNoDataCaptured() {
    RequestContext requestContext = mock(RequestContext.class);
    when(localRootSpan.getRequestContext()).thenReturn(requestContext);
    when(requestContext.getClientIpAddressData()).thenReturn(null);

    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "It is fine"))));

    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT);

    verify(localRootSpan, never()).setTag(eq(Tags.NETWORK_CLIENT_IP), any(String.class));
    verify(localRootSpan, never()).setTag(eq(Tags.HTTP_CLIENT_IP), any(String.class));
  }

  @Test
  void testEvaluateIsANoopForClientIpTagsWhenNoRequestContext() {
    when(localRootSpan.getRequestContext()).thenReturn(null);

    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "It is fine"))));

    aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT);

    verify(localRootSpan, never()).setTag(eq(Tags.NETWORK_CLIENT_IP), any(String.class));
    verify(localRootSpan, never()).setTag(eq(Tags.HTTP_CLIENT_IP), any(String.class));
  }

  @Test
  void testEvaluateWithApiErrors() {
    List<Map<String, Object>> errors = new ArrayList<>();
    errors.add(mapOf("status", 400.0, "title", "Bad request"));
    AIGuardInternal aiguard = mockClient(new RequestHolder(), 404, mapOf("errors", errors));

    AIGuard.AIGuardClientError exception =
        assertThrows(
            AIGuard.AIGuardClientError.class,
            () -> aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT));
    assertEquals(errors, exception.getErrors());
    verify(span).addThrowable(any(AIGuard.AIGuardClientError.class));
    assertTelemetry("ai_guard.requests", "error:true");
  }

  @Test
  void testEvaluateWithInvalidJson() {
    AIGuardInternal aiguard =
        mockClient(new RequestHolder(), 200, mapOf("bad", "This is an invalid response"));

    assertThrows(
        AIGuard.AIGuardClientError.class,
        () -> aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT));
    verify(span).addThrowable(any(AIGuard.AIGuardClientError.class));
    assertTelemetry("ai_guard.requests", "error:true");
  }

  @Test
  void testEvaluateWithMissingAction() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("reason", "I miss something"))));

    assertThrows(
        AIGuard.AIGuardClientError.class,
        () -> aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT));
    verify(span).addThrowable(any(AIGuard.AIGuardClientError.class));
    assertTelemetry("ai_guard.requests", "error:true");
  }

  @Test
  void testEvaluateWithNonJsonResponse() {
    AIGuardInternal aiguard = mockClient(new RequestHolder(), 200, "I am no JSON");

    assertThrows(
        AIGuard.AIGuardClientError.class,
        () -> aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT));
    verify(span).addThrowable(any(AIGuard.AIGuardClientError.class));
    assertTelemetry("ai_guard.requests", "error:true");
  }

  @Test
  void testEvaluateWithEmptyResponse() {
    AIGuardInternal aiguard = mockClient(new RequestHolder(), 200, null);

    assertThrows(
        AIGuard.AIGuardClientError.class,
        () -> aiguard.evaluate(TOOL_CALL, AIGuard.Options.DEFAULT));
    verify(span).addThrowable(any(AIGuard.AIGuardClientError.class));
    assertTelemetry("ai_guard.requests", "error:true");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testMessageLengthTruncation() {
    int maxMessages = Config.get().getAiGuardMaxMessagesLength();
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "It is fine"))));

    List<AIGuard.Message> messages = new ArrayList<>();
    for (int i = 0; i <= maxMessages; i++) {
      messages.add(AIGuard.Message.message("user", "This is a prompt: " + i));
    }

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    assertEquals(maxMessages, received.size());
    assertTrue(received.size() < messages.size());
    assertTelemetry("ai_guard.truncated", "type:messages");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testMessageContentTruncation() {
    int maxContent = Config.get().getAiGuardMaxContentSize();
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "It is fine"))));

    StringBuilder content = new StringBuilder();
    for (int i = 0; i <= maxContent; i++) {
      content.append('A');
    }
    AIGuard.Message message = AIGuard.Message.message("user", content.toString());

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(singletonList(message), AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    AIGuard.Message last = received.get(received.size() - 1);
    assertEquals(maxContent, last.getContent().length());
    assertTrue(last.getContent().length() < message.getContent().length());
    assertTelemetry("ai_guard.truncated", "type:content");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testNoMessagesArguments")
  void testNoMessages(String scenario, List<AIGuard.Message> messages) {
    AIGuardInternal aiguard = new AIGuardInternal(URL, HEADERS, mock(OkHttpClient.class));
    assertThrows(
        IllegalArgumentException.class, () -> aiguard.evaluate(messages, AIGuard.Options.DEFAULT));
  }

  static Stream<Arguments> testNoMessagesArguments() {
    return Stream.of(arguments("empty list", emptyList()), arguments("null list", null));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testEvaluateWithSdsFindings() {
    List<Map<String, Object>> sdsFindings = new ArrayList<>();
    Map<String, Object> finding1 = new LinkedHashMap<>();
    finding1.put("rule_display_name", "Credit Card Number");
    finding1.put("rule_tag", "credit_card");
    finding1.put("category", "pii");
    finding1.put("matched_text", "4111111111111111");
    finding1.put(
        "location",
        mapOf(
            "start_index",
            10.0,
            "end_index_exclusive",
            26.0,
            "path",
            "messages[0].content[0].text"));
    sdsFindings.add(finding1);
    Map<String, Object> finding2 = new LinkedHashMap<>();
    finding2.put("rule_display_name", "Social Security Number");
    finding2.put("rule_tag", "ssn");
    finding2.put("category", "pii");
    finding2.put("matched_text", "123-45-6789");
    finding2.put(
        "location",
        mapOf(
            "start_index",
            30.0,
            "end_index_exclusive",
            41.0,
            "path",
            "messages[1].tool_calls[0].function.arguments"));
    sdsFindings.add(finding2);

    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf(
                "data",
                mapOf(
                    "attributes",
                    mapOf(
                        "action", "ALLOW", "reason", "It is fine", "sds_findings", sdsFindings))));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    AIGuard.Evaluation result = aiguard.evaluate(PROMPT, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    Map<String, Object> receivedMeta = metaCaptor.getValue();
    assertEquals(sdsFindings, receivedMeta.get("sds"));
    assertEquals(sdsFindings, result.getSds());
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testEvaluateWithEmptySdsFindingsArguments")
  @SuppressWarnings("unchecked")
  void testEvaluateWithEmptySdsFindings(String scenario, List<?> sdsFindings) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("action", "ALLOW");
    attrs.put("reason", "It is fine");
    attrs.put("sds_findings", sdsFindings);
    AIGuardInternal aiguard =
        mockClient(new RequestHolder(), 200, mapOf("data", mapOf("attributes", attrs)));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    AIGuard.Evaluation result = aiguard.evaluate(PROMPT, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    Map<String, Object> receivedMeta = metaCaptor.getValue();
    assertFalse(receivedMeta.containsKey("sds"));
    assertEquals(sdsFindings != null ? sdsFindings : emptyList(), result.getSds());
  }

  static Stream<Arguments> testEvaluateWithEmptySdsFindingsArguments() {
    return Stream.of(arguments("null findings", null), arguments("empty findings", emptyList()));
  }

  @Test
  void testEvaluateWithSdsFindingsInAbortError() {
    List<Map<String, Object>> sdsFindings = new ArrayList<>();
    Map<String, Object> finding = new LinkedHashMap<>();
    finding.put("rule_display_name", "Credit Card Number");
    finding.put("rule_tag", "credit_card");
    finding.put("category", "pii");
    finding.put("matched_text", "4111111111111111");
    finding.put(
        "location",
        mapOf(
            "start_index",
            10.0,
            "end_index_exclusive",
            26.0,
            "path",
            "messages[0].content[0].text"));
    sdsFindings.add(finding);

    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf(
                "data",
                mapOf(
                    "attributes",
                    mapOf(
                        "action",
                        "ABORT",
                        "reason",
                        "PII detected",
                        "tags",
                        singletonList("pii"),
                        "sds_findings",
                        sdsFindings,
                        "is_blocking_enabled",
                        true))));

    AIGuard.AIGuardAbortError error =
        assertThrows(
            AIGuard.AIGuardAbortError.class,
            () -> aiguard.evaluate(PROMPT, new AIGuard.Options().block(true)));
    assertEquals(sdsFindings, error.getSds());
  }

  @Test
  void testMissingToolName() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Just do it"))));

    aiguard.evaluate(
        singletonList(AIGuard.Message.tool("call_1", "Content")), AIGuard.Options.DEFAULT);

    verify(span).setTag(AIGuardInternal.TARGET_TAG, "tool");
    verify(span, never()).setTag(eq(AIGuardInternal.TOOL_TAG), anyString());
  }

  @Test
  void mapRequiresEvenNumberOfParams() throws Exception {
    Method mapOf = AIGuardInternal.class.getDeclaredMethod("mapOf", String[].class);
    mapOf.setAccessible(true);
    InvocationTargetException invocation =
        assertThrows(
            InvocationTargetException.class,
            () -> mapOf.invoke(null, (Object) new String[] {"1", "2", "3"}));
    assertTrue(invocation.getTargetException() instanceof IllegalArgumentException);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testMessageImmutability() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Just do it"))));

    List<AIGuard.ToolCall> toolCalls = new ArrayList<>();
    toolCalls.add(AIGuard.ToolCall.toolCall("call_1", "execute_shell", "{\"cmd\": \"ls -lah\"}"));
    List<AIGuard.Message> messages = new ArrayList<>();
    messages.add(new AIGuard.Message("assistant", (String) null, toolCalls, null));

    doAnswer(
            invocation -> {
              messages
                  .get(0)
                  .getToolCalls()
                  .add(
                      AIGuard.ToolCall.toolCall(
                          "call_2", "execute_shell", "{\"cmd\": \"rm -rf\"}"));
              messages.add(AIGuard.Message.tool("call_1", "dir1, dir2, dir3"));
              return null;
            })
        .when(span)
        .finish();

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> metaStructMessages =
        (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    assertNotEquals(messages.size(), metaStructMessages.size());
    assertEquals(1, metaStructMessages.size());
    assertNotEquals(
        messages.get(0).getToolCalls().size(), metaStructMessages.get(0).getToolCalls().size());
    assertEquals(1, metaStructMessages.get(0).getToolCalls().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testJsonSerializationWithTextContentParts() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Good"))));

    List<AIGuard.Message> messages =
        singletonList(
            AIGuard.Message.message(
                "user", singletonList(AIGuard.ContentPart.text("Hello world"))));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    assertEquals(1, received.size());
    assertEquals(1, received.get(0).getContentParts().size());
    assertEquals(AIGuard.ContentPart.Type.TEXT, received.get(0).getContentParts().get(0).getType());
    assertEquals("Hello world", received.get(0).getContentParts().get(0).getText());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testJsonSerializationWithImageUrlContentParts() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Good"))));

    List<AIGuard.Message> messages =
        singletonList(
            AIGuard.Message.message(
                "user",
                singletonList(AIGuard.ContentPart.imageUrl("https://example.com/image.jpg"))));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    assertEquals(1, received.size());
    assertEquals(1, received.get(0).getContentParts().size());
    assertEquals(
        AIGuard.ContentPart.Type.IMAGE_URL, received.get(0).getContentParts().get(0).getType());
    assertEquals(
        "https://example.com/image.jpg",
        received.get(0).getContentParts().get(0).getImageUrl().getUrl());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testJsonSerializationWithMixedContentParts() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Good"))));

    List<AIGuard.ContentPart> parts =
        Arrays.asList(
            AIGuard.ContentPart.text("Describe this image:"),
            AIGuard.ContentPart.imageUrl("https://example.com/image.jpg"),
            AIGuard.ContentPart.text("What do you see?"));
    List<AIGuard.Message> messages = singletonList(AIGuard.Message.message("user", parts));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    assertEquals(1, received.size());
    List<AIGuard.ContentPart> rcvParts = received.get(0).getContentParts();
    assertEquals(3, rcvParts.size());
    assertEquals(AIGuard.ContentPart.Type.TEXT, rcvParts.get(0).getType());
    assertEquals("Describe this image:", rcvParts.get(0).getText());
    assertEquals(AIGuard.ContentPart.Type.IMAGE_URL, rcvParts.get(1).getType());
    assertEquals("https://example.com/image.jpg", rcvParts.get(1).getImageUrl().getUrl());
    assertEquals(AIGuard.ContentPart.Type.TEXT, rcvParts.get(2).getType());
    assertEquals("What do you see?", rcvParts.get(2).getText());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testContentPartsOrderIsPreserved() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Good"))));

    List<AIGuard.ContentPart> parts = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      parts.add(
          i % 2 == 0
              ? AIGuard.ContentPart.text("Text " + i)
              : AIGuard.ContentPart.imageUrl("https://example.com/image" + i + ".jpg"));
    }
    List<AIGuard.Message> messages = singletonList(AIGuard.Message.message("user", parts));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    List<AIGuard.ContentPart> rcvParts = received.get(0).getContentParts();
    assertEquals(5, rcvParts.size());
    for (int i = 0; i < 5; i++) {
      if (i % 2 == 0) {
        assertEquals(AIGuard.ContentPart.Type.TEXT, rcvParts.get(i).getType());
        assertEquals("Text " + i, rcvParts.get(i).getText());
      } else {
        assertEquals(AIGuard.ContentPart.Type.IMAGE_URL, rcvParts.get(i).getType());
        assertEquals(
            "https://example.com/image" + i + ".jpg", rcvParts.get(i).getImageUrl().getUrl());
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testContentPartTextTruncation() {
    int maxContent = Config.get().getAiGuardMaxContentSize();
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Good"))));

    StringBuilder longText = new StringBuilder();
    for (int i = 0; i <= maxContent; i++) {
      longText.append('A');
    }
    List<AIGuard.Message> messages =
        singletonList(
            AIGuard.Message.message(
                "user",
                Arrays.asList(
                    AIGuard.ContentPart.text(longText.toString()),
                    AIGuard.ContentPart.text("Short text"))));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    List<AIGuard.ContentPart> rcvParts = received.get(0).getContentParts();
    assertEquals(2, rcvParts.size());
    assertEquals(maxContent, rcvParts.get(0).getText().length());
    assertTrue(rcvParts.get(0).getText().length() < longText.length());
    assertEquals("Short text", rcvParts.get(1).getText());
    assertTelemetry("ai_guard.truncated", "type:content");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testContentPartImageUrlNotTruncatedEvenWithLongDataUri() {
    int maxContent = Config.get().getAiGuardMaxContentSize();
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Good"))));

    StringBuilder data = new StringBuilder("data:image/png;base64,");
    for (int i = 0; i <= maxContent + 1000; i++) {
      data.append('A');
    }
    String longDataUri = data.toString();
    List<AIGuard.Message> messages =
        singletonList(
            AIGuard.Message.message(
                "user",
                Arrays.asList(
                    AIGuard.ContentPart.text("Image:"),
                    AIGuard.ContentPart.imageUrl(longDataUri))));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    List<AIGuard.ContentPart> rcvParts = received.get(0).getContentParts();
    assertEquals(2, rcvParts.size());
    assertEquals(AIGuard.ContentPart.Type.IMAGE_URL, rcvParts.get(1).getType());
    assertEquals(longDataUri, rcvParts.get(1).getImageUrl().getUrl());
    assertTrue(rcvParts.get(1).getImageUrl().getUrl().length() > maxContent);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testBackwardCompatibilityWithStringContent() {
    AIGuardInternal aiguard =
        mockClient(
            new RequestHolder(),
            200,
            mapOf("data", mapOf("attributes", mapOf("action", "ALLOW", "reason", "Good"))));

    List<AIGuard.Message> messages = singletonList(AIGuard.Message.message("user", "Hello world"));

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
    aiguard.evaluate(messages, AIGuard.Options.DEFAULT);

    verify(span).setMetaStruct(eq(AIGuardInternal.META_STRUCT_TAG), metaCaptor.capture());
    List<AIGuard.Message> received = (List<AIGuard.Message>) metaCaptor.getValue().get("messages");
    assertEquals(1, received.size());
    assertEquals("Hello world", received.get(0).getContent());
    assertNull(received.get(0).getContentParts());
  }

  private AIGuardInternal mockClient(RequestHolder holder, int status, Object response) {
    Call call = mock(Call.class);
    try {
      when(call.execute()).thenAnswer(invocation -> mockResponse(holder.request, status, response));
    } catch (IOException e) {
      fail(e);
    }
    OkHttpClient client = mock(OkHttpClient.class);
    when(client.newCall(any(Request.class)))
        .thenAnswer(
            invocation -> {
              holder.request = invocation.getArgument(0);
              return call;
            });
    return new AIGuardInternal(URL, HEADERS, client);
  }

  private static void assertTelemetry(String metric, String... tags) {
    WafMetricCollector wafMetricCollector = WafMetricCollector.get();
    wafMetricCollector.prepareMetrics();
    Collection<WafMetricCollector.WafMetric> metrics = wafMetricCollector.drain();
    List<String> tagList = Arrays.asList(tags);
    List<WafMetricCollector.WafMetric> filtered =
        metrics.stream()
            .filter(
                m ->
                    "appsec".equals(m.namespace)
                        && metric.equals(m.metricName)
                        && tagList.equals(m.tags))
            .collect(Collectors.toList());
    assertEquals(1, filtered.size(), () -> "metrics: " + metrics);
    long sum = filtered.stream().mapToLong(m -> m.value.longValue()).sum();
    assertEquals(1, sum);
  }

  @SuppressWarnings("unchecked")
  private static void assertMeta(Map<String, Object> meta, TestSuite suite) throws Exception {
    if (suite.tags != null && !suite.tags.isEmpty()) {
      assertEquals(suite.tags, meta.get("attack_categories"));
    }
    if (suite.tagProbabilities != null && !suite.tagProbabilities.isEmpty()) {
      assertEquals(suite.tagProbabilities, meta.get("tag_probs"));
    }
    String receivedMessages = snakeCaseJson(meta.get("messages"));
    String expectedMessages = snakeCaseJson(suite.messages);
    JSONAssert.assertEquals(expectedMessages, receivedMessages, JSONCompareMode.NON_EXTENSIBLE);
  }

  private static void assertRequest(Request request, List<AIGuard.Message> messages)
      throws Exception {
    assertEquals(URL, request.url());
    assertEquals("POST", request.method());
    for (Map.Entry<String, String> entry : HEADERS.entrySet()) {
      assertEquals(entry.getValue(), request.header(entry.getKey()));
    }
    assertTrue(request.body().contentType().toString().contains("application/json"));
    String receivedBody = readRequestBody(request.body());
    Map<String, Object> expected =
        mapOf(
            "data",
            mapOf(
                "attributes",
                mapOf(
                    "messages",
                    messages,
                    "meta",
                    mapOf("service", "ai_guard_test", "env", "test"))));
    String expectedBody = snakeCaseJson(expected);
    JSONAssert.assertEquals(expectedBody, receivedBody, JSONCompareMode.NON_EXTENSIBLE);
  }

  private static String snakeCaseJson(Object value) throws Exception {
    return MAPPER.writeValueAsString(value);
  }

  private static String readRequestBody(RequestBody body) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    BufferedSink sink = Okio.buffer(Okio.sink(output));
    body.writeTo(sink);
    sink.flush();
    return new String(output.toByteArray());
  }

  private static Response mockResponse(Request request, int status, Object body) {
    Response.Builder builder =
        new Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .message("ok")
            .request(request)
            .code(status);
    if (body != null) {
      String json = MOSHI.adapter(Object.class).toJson(body);
      builder.body(ResponseBody.create(MediaType.parse("application/json"), json));
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Map<K, V> mapOf(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("Expected even number of arguments");
    }
    Map<K, V> map = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      map.put((K) entries[i], (V) entries[i + 1]);
    }
    return map;
  }

  private static class RequestHolder {
    Request request;
  }

  private static class TestSuite {
    final AIGuard.Action action;
    final String reason;
    final List<String> tags;
    final Map<String, Number> tagProbabilities;
    final boolean blocking;
    final String description;
    final String target;
    final List<AIGuard.Message> messages;

    TestSuite(
        AIGuard.Action action,
        String reason,
        Map<String, Number> tagProbabilities,
        boolean blocking,
        String description,
        String target,
        List<AIGuard.Message> messages) {
      this.action = action;
      this.reason = reason;
      this.tags = new ArrayList<>(tagProbabilities.keySet());
      this.tagProbabilities = tagProbabilities;
      this.blocking = blocking;
      this.description = description;
      this.target = target;
      this.messages = messages;
    }

    static List<TestSuite> build() {
      List<TestSuite> all = new ArrayList<>();
      Object[][] actionValues = {
        {ALLOW, "Go ahead", emptyMap()},
        {DENY, "Nope", probs("deny_everything", 0.2, "test_deny", 0.8)},
        {ABORT, "Kill it with fire", probs("alarm_tag", 0.1, "abort_everything", 0.9)}
      };
      Object[][] suiteValues = {
        {"tool call", "tool", TOOL_CALL},
        {"tool output", "tool", TOOL_OUTPUT},
        {"prompt", "prompt", PROMPT}
      };
      for (Object[] action : actionValues) {
        for (boolean blocking : new boolean[] {true, false}) {
          for (Object[] suite : suiteValues) {
            @SuppressWarnings("unchecked")
            Map<String, Number> tagProbs = (Map<String, Number>) action[2];
            @SuppressWarnings("unchecked")
            List<AIGuard.Message> messages = (List<AIGuard.Message>) suite[2];
            all.add(
                new TestSuite(
                    (AIGuard.Action) action[0],
                    (String) action[1],
                    tagProbs,
                    blocking,
                    (String) suite[0],
                    (String) suite[1],
                    messages));
          }
        }
      }
      return all;
    }

    private static Map<String, Number> probs(Object... entries) {
      Map<String, Number> result = new LinkedHashMap<>();
      for (int i = 0; i < entries.length; i += 2) {
        result.put((String) entries[i], (Number) entries[i + 1]);
      }
      return result;
    }

    @Override
    public String toString() {
      List<Object> contents = new ArrayList<>();
      for (AIGuard.Message m : messages) {
        contents.add(m.getContent());
      }
      return "TestSuite{description='"
          + description
          + "', action="
          + action
          + ", reason='"
          + reason
          + "', blocking="
          + blocking
          + ", target='"
          + target
          + "', messages="
          + contents
          + "', tags="
          + tags
          + "}";
    }
  }
}
