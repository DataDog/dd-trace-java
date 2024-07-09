package datadog.trace.bootstrap.instrumentation.api;

public interface AgentTraceCollector {
  void registerContinuation(AgentScope.Continuation continuation);

  void cancelContinuation(AgentScope.Continuation continuation);
}
