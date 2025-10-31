package datadog.trace.bootstrap.instrumentation.api;

/**
 * Interface implemented by span wrappers such as OpenTelemetry and OpenTracing spans. Provides a
 * callback that is invoked when the underlying span is finished. This guarantees that wrapper
 * specific logic runs when spans are auto-finished.
 */
public interface SpanWrapper {
  default void onSpanFinished() {}
}
