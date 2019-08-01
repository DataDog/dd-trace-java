package datadog.trace.instrumentation.api;

public interface AgentSpan {
  AgentSpan setMetadata(String key, boolean value);

  AgentSpan setMetadata(String key, int value);

  AgentSpan setMetadata(String key, long value);

  AgentSpan setMetadata(String key, double value);

  AgentSpan setMetadata(String key, String value);

  AgentSpan setError(boolean error);

  AgentSpan addThrowable(Throwable throwable);

  //  AgentContext context();

  void finish();

  interface Context {}
}
