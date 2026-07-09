package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.junit.jupiter.api.Test;

class CoreTracerCarrierRebindTest {

  @Test
  void tracerRebindUnbindAreSafeNoOpsWithoutProfiling() {
    CoreTracer tracer = CoreTracer.builder().build();
    AgentSpan span = tracer.startSpan("test", "op");
    try (AgentScopeCloser ignored = new AgentScopeCloser(tracer.activateSpan(span))) {
      // Profiling is disabled by default, so both calls must be harmless no-ops.
      assertDoesNotThrow(tracer::rebindProfilingContextToCarrier);
      assertDoesNotThrow(tracer::unbindProfilingContextFromCarrier);
    } finally {
      span.finish();
    }
  }

  @Test
  void noopTracerRebindUnbindDoNotThrow() {
    AgentTracer.TracerAPI noop = AgentTracer.NOOP_TRACER;
    assertDoesNotThrow(noop::rebindProfilingContextToCarrier);
    assertDoesNotThrow(noop::unbindProfilingContextFromCarrier);
  }

  /** Minimal try-with-resources helper around AgentScope. */
  static final class AgentScopeCloser implements AutoCloseable {
    private final datadog.trace.bootstrap.instrumentation.api.AgentScope scope;

    AgentScopeCloser(datadog.trace.bootstrap.instrumentation.api.AgentScope scope) {
      this.scope = scope;
    }

    @Override
    public void close() {
      scope.close();
    }
  }
}
