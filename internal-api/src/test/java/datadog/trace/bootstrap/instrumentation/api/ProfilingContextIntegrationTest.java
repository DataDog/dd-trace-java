package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.api.EndpointTracker;
import datadog.trace.api.profiling.Timing;
import org.junit.jupiter.api.Test;

class ProfilingContextIntegrationTest {
  @Test
  void defaultIntegrationIsNotCarrierThreadBound() {
    ProfilingContextIntegration integration =
        new ProfilingContextIntegration() {
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
    assertFalse(integration.isCarrierThreadBound());
  }

  @Test
  void noOpIntegrationIsNotCarrierThreadBound() {
    assertFalse(ProfilingContextIntegration.NoOp.INSTANCE.isCarrierThreadBound());
  }
}
