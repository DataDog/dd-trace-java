package datadog.trace.bootstrap.instrumentation.api;

/** Interface that provides the ability to append a span link */
@FunctionalInterface
public interface AppendableSpanLinks {
  void addLink(AgentSpanLink link);
}
