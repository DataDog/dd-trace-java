package datadog.trace.api.internal;

/**
 * Tracer internal features. Those features are not part of public API and can change or be removed
 * at any time.
 */
public interface InternalTracer {
  void flush();

  void flushMetrics();
}
