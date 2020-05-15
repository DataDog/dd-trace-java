package datadog.trace.bootstrap.instrumentation.api;

public interface AgentTrace {
  void registerContinuation(AgentScope.Continuation continuation);

  void cancelContinuation(AgentScope.Continuation continuation);
}
