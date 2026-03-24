package datadog.trace.bootstrap.instrumentation.api;

@FunctionalInterface
public interface WritableSpanLinks {
  public void addLink(AgentSpanLink link);
}
