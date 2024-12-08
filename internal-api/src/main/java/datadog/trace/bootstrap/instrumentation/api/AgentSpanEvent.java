package datadog.trace.bootstrap.instrumentation.api;

/**
 * This interface describes a span event.
 */
public interface AgentSpanEvent {
  long timestamp();

  String name();

  String attributes();
}
