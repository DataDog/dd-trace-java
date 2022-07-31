package datadog.trace.api.profiling;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

final class TestTracingContextTrackerFactory
    implements TracingContextTrackerFactory.Implementation {
  @Override
  public TracingContextTracker contextTrackerInstance(AgentSpan span) {
    return new TestTracingContextTracker();
  }
}
