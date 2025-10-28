package datadog.trace.bootstrap.instrumentation.api;

public interface SpanWrapper {
  default void onSpanFinished() {}
}
