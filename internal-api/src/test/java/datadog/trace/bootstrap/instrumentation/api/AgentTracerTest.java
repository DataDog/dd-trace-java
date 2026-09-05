package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentTracerTest {

  @Test
  void noopTracerTreatsMetricsShutdownAsSuccessful() {
    assertTrue(new AgentTracer.NoopTracerAPI().shutdownOtelMetrics().isSuccess());
  }
}
