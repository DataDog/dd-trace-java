package datadog.trace.api.llmobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.ContextScope;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.llmobs.LLMObsParent.Source;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import org.junit.jupiter.api.Test;

class LLMObsParentTest {

  private static final DDTraceId TRACE = DDTraceId.from(1234);
  private static final DDTraceId OTHER_TRACE = DDTraceId.from(5678);

  /** 0x6ab41f6e00000000fea4f6b7295bd5e9, as the propagation tag carries it. */
  private static final String WIRE_TRACE_ID = "141833418457532941290148377431948121577";

  private static final String STORED_TRACE_ID = "6ab41f6e00000000fea4f6b7295bd5e9";

  @Test
  void noContextAndNothingPropagatedIsARoot() {
    LLMObsParent parent = LLMObsParent.resolve(span(TRACE, null));

    assertEquals(Source.NONE, parent.source);
    assertTrue(parent.isRoot());
    assertEquals(LLMObsContext.ROOT_SPAN_ID, parent.parentId);
    assertNull(parent.traceId);
    assertNull(parent.mlApp);
    assertNull(parent.sessionId);
    assertNull(parent.sampleRate);
    assertNull(parent.samplingDecision);
    assertNull(parent.ignoredContext);
  }

  @Test
  void sameTraceInProcessContextWinsOverPropagatedValues() {
    AgentSpanContext active = spanContext(TRACE, 42);
    try (ContextScope ignored =
        LLMObsContext.attach(
            active,
            "local-trace",
            "local-app",
            "local-session",
            "v1",
            "0.5",
            LLMObsContext.SAMPLING_DECISION_SAMPLED,
            "7",
            "local-agent")) {
      LLMObsParent parent = LLMObsParent.resolve(span(TRACE, propagated()));

      assertEquals(Source.IN_PROCESS, parent.source);
      assertFalse(parent.isRoot());
      assertEquals("42", parent.parentId);
      assertEquals("local-trace", parent.traceId);
      assertEquals("local-app", parent.mlApp);
      assertEquals("local-session", parent.sessionId);
      assertEquals("v1", parent.agentVersion);
      assertEquals("7", parent.parentAgentSpanId);
      assertEquals("local-agent", parent.parentAgentName);
      assertEquals("0.5", parent.sampleRate);
      assertEquals(LLMObsContext.SAMPLING_DECISION_SAMPLED, parent.samplingDecision);
      assertNull(parent.ignoredContext);
    }
  }

  /**
   * In-process values are passed through as the context holds them, partial pairs included: the
   * span re-attaches them for its own descendants and injections, which read each one separately.
   */
  @Test
  void inProcessValuesPassThroughUnpaired() {
    try (ContextScope ignored =
        LLMObsContext.attach(
            spanContext(TRACE, 42),
            null,
            null,
            null,
            null,
            null,
            LLMObsContext.SAMPLING_DECISION_SAMPLED,
            null,
            "orphan-name")) {
      LLMObsParent parent = LLMObsParent.resolve(span(TRACE, null));

      assertEquals(Source.IN_PROCESS, parent.source);
      assertFalse(
          parent.isRoot(), "a parent without a full verdict is not a root and must not roll");
      assertNull(parent.sampleRate);
      assertEquals(LLMObsContext.SAMPLING_DECISION_SAMPLED, parent.samplingDecision);
      assertNull(parent.parentAgentSpanId);
      assertEquals("orphan-name", parent.parentAgentName);
    }
  }

  @Test
  void emptyInProcessValuesReadAsAbsent() {
    try (ContextScope ignored =
        LLMObsContext.attach(spanContext(TRACE, 42), "", "", "", "", "", "", "", "")) {
      LLMObsParent parent = LLMObsParent.resolve(span(TRACE, null));

      assertNull(parent.traceId);
      assertNull(parent.mlApp);
      assertNull(parent.sessionId);
      assertNull(parent.agentVersion);
      assertNull(parent.parentAgentSpanId);
      assertNull(parent.parentAgentName);
      assertNull(parent.sampleRate);
      assertNull(parent.samplingDecision);
    }
  }

  @Test
  void staleContextFallsBackToPropagatedValues() {
    AgentSpanContext stale = spanContext(OTHER_TRACE, 99);
    try (ContextScope ignored = LLMObsContext.attach(stale, "stale-session")) {
      LLMObsParent parent = LLMObsParent.resolve(span(TRACE, propagated()));

      assertEquals(Source.PROPAGATED, parent.source);
      assertEquals("upstream-session", parent.sessionId);
      assertSame(stale, parent.ignoredContext);
    }
  }

  @Test
  void staleContextWithNothingPropagatedIsARootThatReportsTheIgnoredContext() {
    AgentSpanContext stale = spanContext(OTHER_TRACE, 99);
    try (ContextScope ignored =
        LLMObsContext.attach(
            stale,
            null,
            "stale-app",
            "stale-session",
            "stale-v",
            "0.25",
            LLMObsContext.SAMPLING_DECISION_DROPPED,
            "8",
            "stale-agent")) {
      LLMObsParent parent = LLMObsParent.resolve(span(TRACE, null));

      assertTrue(parent.isRoot());
      assertEquals(LLMObsContext.ROOT_SPAN_ID, parent.parentId);
      assertNull(parent.mlApp);
      assertNull(parent.sessionId);
      assertNull(parent.agentVersion);
      assertNull(parent.parentAgentSpanId);
      assertNull(parent.sampleRate);
      assertNull(parent.samplingDecision);
      assertSame(stale, parent.ignoredContext);
    }
  }

  @Test
  void propagatedValuesAreAdoptedWithTheTraceIdConvertedFromTheWire() {
    LLMObsParent parent = LLMObsParent.resolve(span(TRACE, propagated()));

    assertEquals(Source.PROPAGATED, parent.source);
    assertFalse(parent.isRoot());
    assertEquals("12571653173807582101", parent.parentId);
    assertEquals(STORED_TRACE_ID, parent.traceId);
    assertEquals("upstream-app", parent.mlApp);
    assertEquals("upstream-session", parent.sessionId);
    assertNull(parent.agentVersion, "agent_version is not propagated");
    assertEquals("555", parent.parentAgentSpanId);
    assertEquals("planner-agent", parent.parentAgentName);
    assertEquals("0.25", parent.sampleRate);
    assertEquals(LLMObsContext.SAMPLING_DECISION_DROPPED, parent.samplingDecision);
  }

  /**
   * Context that arrived without a parent id or a verdict still continues the caller's trace: the
   * span is not a root, so it neither claims a parent of its own nor rolls a decision.
   */
  @Test
  void propagatedContextWithoutParentIdOrVerdictIsNotARoot() {
    LLMObsParent parent =
        LLMObsParent.resolve(
            span(
                TRACE,
                new LLMObsPropagationValues(
                    WIRE_TRACE_ID, "upstream-app", null, null, null, null, null, null)));

    assertEquals(Source.PROPAGATED, parent.source);
    assertFalse(parent.isRoot());
    assertEquals(LLMObsContext.ROOT_SPAN_ID, parent.parentId);
    assertNull(parent.sampleRate);
    assertNull(parent.samplingDecision);
  }

  @Test
  void propagatedRateWithoutADecisionIsDropped() {
    LLMObsParent parent =
        LLMObsParent.resolve(
            span(
                TRACE,
                new LLMObsPropagationValues(null, null, null, null, null, "1", "0.5", null)));

    assertNull(parent.sampleRate);
    assertNull(parent.samplingDecision);
  }

  @Test
  void propagatedDecisionWithoutARateIsDropped() {
    LLMObsParent parent =
        LLMObsParent.resolve(
            span(
                TRACE,
                new LLMObsPropagationValues(
                    null,
                    null,
                    null,
                    null,
                    null,
                    "1",
                    null,
                    LLMObsContext.SAMPLING_DECISION_SAMPLED)));

    assertNull(parent.sampleRate);
    assertNull(parent.samplingDecision);
  }

  @Test
  void propagatedAgentNameWithoutAnIdIsDropped() {
    LLMObsParent parent =
        LLMObsParent.resolve(
            span(
                TRACE,
                new LLMObsPropagationValues(
                    null, null, null, null, "planner-agent", "1", null, null)));

    assertNull(parent.parentAgentSpanId);
    assertNull(parent.parentAgentName);
  }

  private static LLMObsPropagationValues propagated() {
    return new LLMObsPropagationValues(
        WIRE_TRACE_ID,
        "upstream-app",
        "upstream-session",
        "555",
        "planner-agent",
        "12571653173807582101",
        "0.25",
        LLMObsContext.SAMPLING_DECISION_DROPPED);
  }

  private static AgentSpanContext spanContext(DDTraceId traceId, long spanId) {
    AgentSpanContext context = mock(AgentSpanContext.class);
    when(context.getTraceId()).thenReturn(traceId);
    when(context.getSpanId()).thenReturn(spanId);
    return context;
  }

  private static AgentSpan span(DDTraceId traceId, LLMObsPropagationValues extracted) {
    AgentSpanContext context = mock(AgentSpanContext.class);
    when(context.getExtractedLLMObsValues()).thenReturn(extracted);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTraceId()).thenReturn(traceId);
    when(span.spanContext()).thenReturn(context);
    return span;
  }
}
