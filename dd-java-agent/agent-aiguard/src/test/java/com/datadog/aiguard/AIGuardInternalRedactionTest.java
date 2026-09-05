package com.datadog.aiguard;

import static com.datadog.aiguard.AIGuardInternal.META_STRUCT_MESSAGES;
import static com.datadog.aiguard.AIGuardInternal.META_STRUCT_TAG;
import static com.datadog.aiguard.AIGuardInternal.REDACTED_TAG;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.api.aiguard.AIGuard.AIGuardAbortError;
import datadog.trace.api.aiguard.AIGuard.AIGuardClientError;
import datadog.trace.api.aiguard.AIGuard.Evaluation;
import datadog.trace.api.aiguard.AIGuard.Message;
import datadog.trace.api.aiguard.AIGuard.Options;
import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Covers how {@link AIGuardInternal} wires the backend redaction decision into an evaluation. */
@ExtendWith(WithConfigExtension.class)
class AIGuardInternalRedactionTest {

  private static final HttpUrl URL =
      HttpUrl.parse("https://app.datadoghq.com/api/v2/ai-guard/evaluate");

  private static final String SENSITIVE = "My SSN is 123-45-6789";
  private static final String REDACTED = "My SSN is <REDACTED>";

  private static final List<Message> MESSAGES =
      asList(
          Message.message("system", "You are a helpful assistant."),
          Message.message("user", SENSITIVE));

  private AgentSpan span;
  private AgentTracer.TracerAPI originalTracer;
  private Map<String, Object> metaStruct;

  @BeforeEach
  void setUp() {
    originalTracer = AgentTracer.get();

    span = mock(AgentSpan.class);
    lenient().when(span.getLocalRootSpan()).thenReturn(mock(AgentSpan.class));
    doAnswer(
            invocation -> {
              metaStruct = invocation.getArgument(1);
              return span;
            })
        .when(span)
        .setMetaStruct(eq(META_STRUCT_TAG), any());

    final AgentTracer.SpanBuilder builder = mock(AgentTracer.SpanBuilder.class, RETURNS_DEEP_STUBS);
    lenient().when(builder.start()).thenReturn(span);
    final AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    lenient().when(tracer.buildSpan(anyString(), anyString())).thenReturn(builder);
    lenient().when(tracer.activateSpan(any())).thenReturn(mock(AgentScope.class));
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
    AIGuardInternal.uninstall();
  }

  @Test
  void redactsMessagesAndRewritesMetaStruct() {
    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, replacements(REDACTED)));

    assertEquals(REDACTED, evaluation.getMessages().get(1).getContent());
    // the untouched message is carried over as is
    assertEquals("You are a helpful assistant.", evaluation.getMessages().get(0).getContent());
    assertEquals(REDACTED, metaStructMessages().get(1).getContent());
    verify(span).setTag(REDACTED_TAG, true);
    // the caller's list is left alone
    assertEquals(SENSITIVE, MESSAGES.get(1).getContent());
  }

  @Test
  void reportsNotRedactedWhenResponseCarriesNoReplacements() {
    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, null));

    assertSame(MESSAGES, evaluation.getMessages());
    assertEquals(SENSITIVE, metaStructMessages().get(1).getContent());
    verify(span).setTag(REDACTED_TAG, false);
  }

  @Test
  void reportsNotRedactedWhenReplacementsAreEmpty() {
    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, "[]"));

    assertSame(MESSAGES, evaluation.getMessages());
    verify(span).setTag(REDACTED_TAG, false);
  }

  @Test
  void reportsNotRedactedWhenEveryReplacementIsSkipped() {
    final String unresolvable = "[{\"path\":\"messages[9].content\",\"replacement\":\"nope\"}]";

    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, unresolvable));

    assertSame(MESSAGES, evaluation.getMessages());
    assertEquals(SENSITIVE, metaStructMessages().get(1).getContent());
    verify(span).setTag(REDACTED_TAG, false);
  }

  @Test
  void killSwitchLeavesMessagesUntouchedAndEmitsNoTag() {
    WithConfigExtension.injectEnvConfig("DD_AI_GUARD_REDACTION_ENABLED", "false", false);

    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, replacements(REDACTED)));

    assertSame(MESSAGES, evaluation.getMessages());
    assertEquals(SENSITIVE, metaStructMessages().get(1).getContent());
    // an absent tag means "redaction is off", which is distinct from a false one
    verify(span, never()).setTag(eq(REDACTED_TAG), anyBoolean());
  }

  @Test
  void redactsMetaStructOnBlockedEvaluation() {
    final AIGuardAbortError error =
        assertThrows(
            AIGuardAbortError.class,
            () -> evaluate(responseWith("DENY", true, replacements(REDACTED))));

    // reporting is still redacted on the blocked path
    assertEquals(REDACTED, metaStructMessages().get(1).getContent());
    verify(span).setTag(REDACTED_TAG, true);

    // the abort error must not carry the conversation, redacted or otherwise
    assertFalse(errorText(error).contains(SENSITIVE));
    assertFalse(errorText(error).contains(REDACTED));
  }

  /**
   * The meta struct messages are built exactly once per evaluation, in the {@code finally} that
   * closes the request, so one evaluation reports one truncation no matter how much redaction it
   * applied.
   */
  @Test
  void reportsContentTruncationOnlyOnceWhenRedacting() {
    WithConfigExtension.injectEnvConfig("DD_AI_GUARD_MAX_CONTENT_SIZE", "8", false);
    drainTelemetry();

    evaluate(responseWith("ALLOW", false, replacements(REDACTED)));

    assertEquals(1, truncationMetrics());
  }

  @Test
  void reportsRedactedTrueInTelemetryWhenSomethingWasRedacted() {
    drainTelemetry();

    evaluate(responseWith("ALLOW", false, replacements(REDACTED)));

    assertTrue(requestTags().contains("redacted:true"));
  }

  @Test
  void reportsRedactedFalseInTelemetryWhenNothingWasRedacted() {
    drainTelemetry();

    evaluate(responseWith("ALLOW", false, null));

    assertTrue(requestTags().contains("redacted:false"));
  }

  @Test
  void reportsNoRedactedTagInTelemetryWhenKillSwitchIsOff() {
    WithConfigExtension.injectEnvConfig("DD_AI_GUARD_REDACTION_ENABLED", "false", false);
    drainTelemetry();

    evaluate(responseWith("ALLOW", false, replacements(REDACTED)));

    final List<String> tags = requestTags();
    // absence of the tag is what tells a consumer that redaction is off
    assertFalse(tags.contains("redacted:true"));
    assertFalse(tags.contains("redacted:false"));
    assertTrue(tags.contains("error:false"));
  }

  private static List<String> requestTags() {
    WafMetricCollector.get().prepareMetrics();
    for (final MetricCollector.Metric metric : WafMetricCollector.get().drain()) {
      if ("requests".equals(metric.metricName)) {
        return metric.tags;
      }
    }
    throw new AssertionError("no ai_guard requests metric was reported");
  }

  private static void drainTelemetry() {
    WafMetricCollector.get().prepareMetrics();
    WafMetricCollector.get().drain();
  }

  private static long truncationMetrics() {
    WafMetricCollector.get().prepareMetrics();
    long count = 0;
    for (final MetricCollector.Metric metric : WafMetricCollector.get().drain()) {
      if ("truncated".equals(metric.metricName)) {
        count += metric.value.longValue();
      }
    }
    return count;
  }

  @Test
  void reportsTheServiceReplacementsVerbatimIncludingUnappliedEntries() {
    final String replacements =
        "[{\"path\":\"messages[1].content\",\"replacement\":\""
            + REDACTED
            + "\"},{\"path\":\"messages[9].content\",\"replacement\":\"never applied\"}]";

    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, replacements));

    assertEquals(REDACTED, evaluation.getMessages().get(1).getContent());
    // messages[9] cannot resolve and is skipped fail-safe, but the service asked for it, so it is
    // still reported back to the caller
    assertEquals(2, evaluation.getRedactionReplacements().size());
  }

  @Test
  void reportsTheServiceReplacementsEvenWhenTheKillSwitchIsOff() {
    WithConfigExtension.injectEnvConfig("DD_AI_GUARD_REDACTION_ENABLED", "false", false);

    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, replacements(REDACTED)));

    // nothing was applied, but the service's request is still visible to the caller
    assertSame(MESSAGES, evaluation.getMessages());
    assertEquals(1, evaluation.getRedactionReplacements().size());
  }

  @Test
  void reportsNotRedactedWhenTheEvaluationFails() {
    // no action field, so the response is rejected before any redaction work happens
    assertThrows(
        AIGuardClientError.class, () -> evaluate("{\"data\":{\"attributes\":{\"tags\":[]}}}"));

    // a failed evaluation redacted nothing, and must not look like the kill switch is off
    verify(span).setTag(REDACTED_TAG, false);
    verify(span, never()).setTag(REDACTED_TAG, true);
  }

  @Test
  void emitsNoTagWhenTheEvaluationFailsAndTheKillSwitchIsOff() {
    WithConfigExtension.injectEnvConfig("DD_AI_GUARD_REDACTION_ENABLED", "false", false);

    assertThrows(
        AIGuardClientError.class, () -> evaluate("{\"data\":{\"attributes\":{\"tags\":[]}}}"));

    verify(span, never()).setTag(eq(REDACTED_TAG), anyBoolean());
  }

  @Test
  void doesNotFailWhenReplacementsAreMalformed() {
    final Evaluation evaluation = evaluate(responseWith("ALLOW", false, "\"not-an-array\""));

    assertSame(MESSAGES, evaluation.getMessages());
    verify(span).setTag(REDACTED_TAG, false);
  }

  private static String errorText(final AIGuardAbortError error) {
    return String.valueOf(error.getMessage()) + error + String.valueOf(error.getReason());
  }

  @SuppressWarnings("unchecked")
  private List<Message> metaStructMessages() {
    assertNotNull(metaStruct, "meta struct was never attached to the span");
    return (List<Message>) metaStruct.get(META_STRUCT_MESSAGES);
  }

  private static String replacements(final String replacement) {
    return "[{\"path\":\"messages[1].content\",\"replacement\":\"" + replacement + "\"}]";
  }

  private static String responseWith(
      final String action, final boolean blocking, final String redactionReplacements) {
    final StringBuilder attributes = new StringBuilder();
    attributes
        .append("{\"action\":\"")
        .append(action)
        .append("\",\"reason\":\"a reason\",\"is_blocking_enabled\":")
        .append(blocking)
        .append(",\"tags\":[]");
    if (redactionReplacements != null) {
      attributes.append(",\"redaction_replacements\":").append(redactionReplacements);
    }
    attributes.append('}');
    return "{\"data\":{\"id\":\"1\",\"type\":\"evaluations\",\"attributes\":" + attributes + "}}";
  }

  private Evaluation evaluate(final String responseBody) {
    final OkHttpClient client = mock(OkHttpClient.class);
    final Call call = mock(Call.class);
    try {
      lenient().when(call.execute()).thenReturn(response(responseBody));
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
    lenient().when(client.newCall(any(Request.class))).thenReturn(call);
    return new AIGuardInternal(URL, Collections.emptyMap(), client)
        .evaluate(MESSAGES, new Options().block(true));
  }

  private static Response response(final String json) {
    return new Response.Builder()
        .protocol(Protocol.HTTP_1_1)
        .message("ok")
        .request(new Request.Builder().url(URL).build())
        .code(200)
        .body(ResponseBody.create(MediaType.parse("application/json"), json))
        .build();
  }
}
