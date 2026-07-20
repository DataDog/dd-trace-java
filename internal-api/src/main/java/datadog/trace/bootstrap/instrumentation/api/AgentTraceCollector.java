package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.ContextContinuation;

public interface AgentTraceCollector {
  void registerContinuation(ContextContinuation continuation);

  void removeContinuation(ContextContinuation continuation);
}
