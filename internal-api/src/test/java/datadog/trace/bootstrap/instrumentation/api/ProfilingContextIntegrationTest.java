package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import datadog.context.Context;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.profiling.Timing;
import org.junit.jupiter.api.Test;

class ProfilingContextIntegrationTest {

  private static ProfilingContextIntegration bareIntegration() {
    return new ProfilingContextIntegration() {
      @Override
      public String name() {
        return "test-only";
      }

      @Override
      public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

      @Override
      public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
        return EndpointTracker.NO_OP;
      }

      @Override
      public Timing start(TimerType type) {
        return Timing.NoOp.INSTANCE;
      }
    };
  }

  @Test
  void defaultSetAndClearContextAreNoOps() {
    ProfilingContextIntegration integration = bareIntegration();
    assertDoesNotThrow(() -> integration.setContext(Context.root()));
    assertDoesNotThrow(integration::clearContext);
  }

  @Test
  void noOpSetAndClearContextAreNoOps() {
    ProfilingContextIntegration noOp = ProfilingContextIntegration.NoOp.INSTANCE;
    assertDoesNotThrow(() -> noOp.setContext(Context.root()));
    assertDoesNotThrow(noOp::clearContext);
  }
}
