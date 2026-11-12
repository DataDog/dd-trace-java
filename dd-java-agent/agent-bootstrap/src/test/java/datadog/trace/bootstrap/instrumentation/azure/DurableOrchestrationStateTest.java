package datadog.trace.bootstrap.instrumentation.azure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import org.junit.jupiter.api.Test;

class DurableOrchestrationStateTest {

  @Test
  void activatesRemoteParentAndRestoresPreviousContext() {
    AgentSpanContext spanContext = mock(AgentSpanContext.class);
    when(spanContext.getTraceId()).thenReturn(DDTraceId.from(42));
    when(spanContext.getSpanId()).thenReturn(43L);
    AgentSpan parent = AgentSpan.fromSpanContext(spanContext);
    AgentSpan orchestration = mock(AgentSpan.class);

    assertNull(DurableOrchestrationState.current());
    try (ContextScope scope = DurableOrchestrationState.activate(parent, "Orchestrator")) {
      DurableOrchestrationState state = DurableOrchestrationState.current();
      assertSame(parent, state.parentSpan());
      assertSame("Orchestrator", state.functionName());
      assertSame(parent, AgentSpan.fromContext(Context.current()));
      assertFalse(state.errorRecorded());
      state.markErrorRecorded();
      assertTrue(state.errorRecorded());
      assertNull(state.span());
      assertNull(state.setSpan(orchestration));
      assertSame(orchestration, state.span());
      assertSame(orchestration, state.setSpan(null));
      assertNull(state.span());
    }
    assertNull(DurableOrchestrationState.current());
  }

  @Test
  void ignoresMissingAndInvalidRemoteParents() {
    AgentSpanContext invalidContext = mock(AgentSpanContext.class);
    when(invalidContext.getTraceId()).thenReturn(DDTraceId.ZERO);
    when(invalidContext.getSpanId()).thenReturn(0L);
    AgentSpan invalidParent = AgentSpan.fromSpanContext(invalidContext);
    assertFalse(invalidParent.isValid());
    try (ContextScope scope = DurableOrchestrationState.activate(null, null)) {
      assertNull(DurableOrchestrationState.current().parentSpan());
    }
    try (ContextScope scope = DurableOrchestrationState.activate(invalidParent, null)) {
      assertNull(DurableOrchestrationState.current().parentSpan());
      assertNull(AgentSpan.fromContext(Context.current()));
    }
    assertNull(DurableOrchestrationState.current());
  }
}
