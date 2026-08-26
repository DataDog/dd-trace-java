package datadog.trace.api.llmobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import org.junit.jupiter.api.Test;

class LLMObsContextTest {
  @Test
  void rootSpanIdIsUndefined() {
    assertEquals("undefined", LLMObsContext.ROOT_SPAN_ID);
  }

  @Test
  void currentReturnsNullWhenNoContextAttached() {
    assertNull(LLMObsContext.current());
  }

  @Test
  void currentSessionIdReturnsNullWhenNoContextAttached() {
    assertNull(LLMObsContext.currentSessionId());
  }

  @Test
  void attachStoresSpanContext() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx)) {
      assertEquals(ctx, LLMObsContext.current());
    }
    assertNull(LLMObsContext.current());
  }

  @Test
  void attachWithoutSessionIdLeavesSessionIdNull() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx)) {
      assertNull(LLMObsContext.currentSessionId());
    }
  }

  @Test
  void attachWithSessionIdStoresBothContextAndSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, "session-123")) {
      assertEquals(ctx, LLMObsContext.current());
      assertEquals("session-123", LLMObsContext.currentSessionId());
    }
    assertNull(LLMObsContext.current());
    assertNull(LLMObsContext.currentSessionId());
  }

  @Test
  void attachWithNullSessionIdIgnoresSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, null)) {
      assertEquals(ctx, LLMObsContext.current());
      assertNull(LLMObsContext.currentSessionId());
    }
  }

  @Test
  void attachWithEmptySessionIdIgnoresSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, "")) {
      assertEquals(ctx, LLMObsContext.current());
      assertNull(LLMObsContext.currentSessionId());
    }
  }

  @Test
  void nestedScopesRestoreParentContextOnClose() {
    AgentSpanContext outer = mock(AgentSpanContext.class);
    AgentSpanContext inner = mock(AgentSpanContext.class);
    try (ContextScope outerScope = LLMObsContext.attach(outer, "outer-session")) {
      assertEquals(outer, LLMObsContext.current());
      assertEquals("outer-session", LLMObsContext.currentSessionId());
      try (ContextScope innerScope = LLMObsContext.attach(inner, "inner-session")) {
        assertEquals(inner, LLMObsContext.current());
        assertEquals("inner-session", LLMObsContext.currentSessionId());
      }
      assertEquals(outer, LLMObsContext.current());
      assertEquals("outer-session", LLMObsContext.currentSessionId());
    }
    assertNull(LLMObsContext.current());
    assertNull(LLMObsContext.currentSessionId());
  }

  @Test
  void childScopeInheritsParentSessionId() {
    AgentSpanContext parent = mock(AgentSpanContext.class);
    AgentSpanContext child = mock(AgentSpanContext.class);
    try (ContextScope parentScope = LLMObsContext.attach(parent, "inherited-session")) {
      try (ContextScope childScope = LLMObsContext.attach(child)) {
        assertEquals(child, LLMObsContext.current());
        assertEquals("inherited-session", LLMObsContext.currentSessionId());
      }
    }
  }

  @Test
  void samplingValuesReturnNullWhenNoContextAttached() {
    assertNull(LLMObsContext.currentSamplingDecision());
    assertNull(LLMObsContext.currentSampleRate());
  }

  @Test
  void attachWithoutSamplingDecisionLeavesSamplingValuesNull() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, "session-123")) {
      assertNull(LLMObsContext.currentSamplingDecision());
      assertNull(LLMObsContext.currentSampleRate());
    }
  }

  @Test
  void attachWithSamplingDecisionStoresDecisionAndRate() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope =
        LLMObsContext.attach(ctx, null, "0.25", LLMObsContext.SAMPLING_DECISION_DROPPED)) {
      assertEquals(
          LLMObsContext.SAMPLING_DECISION_DROPPED, LLMObsContext.currentSamplingDecision());
      assertEquals("0.25", LLMObsContext.currentSampleRate());
    }
    assertNull(LLMObsContext.currentSamplingDecision());
    assertNull(LLMObsContext.currentSampleRate());
  }

  @Test
  void attachWithNullSamplingDecisionIgnoresSampleRate() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    // The rate is only meaningful alongside a decision, so it is not stored on its own.
    try (ContextScope scope = LLMObsContext.attach(ctx, null, "0.25", null)) {
      assertNull(LLMObsContext.currentSamplingDecision());
      assertNull(LLMObsContext.currentSampleRate());
    }
  }

  @Test
  void childScopeInheritsParentSamplingDecision() {
    AgentSpanContext parent = mock(AgentSpanContext.class);
    AgentSpanContext child = mock(AgentSpanContext.class);
    try (ContextScope parentScope =
        LLMObsContext.attach(parent, null, "1", LLMObsContext.SAMPLING_DECISION_SAMPLED)) {
      try (ContextScope childScope = LLMObsContext.attach(child)) {
        assertEquals(child, LLMObsContext.current());
        assertEquals(
            LLMObsContext.SAMPLING_DECISION_SAMPLED, LLMObsContext.currentSamplingDecision());
        assertEquals("1", LLMObsContext.currentSampleRate());
      }
    }
  }
}
