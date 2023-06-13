package datadog.trace.core.metrics;

public interface SpanMetrics {
  SpanMetrics NOOP =
      new SpanMetrics() {
        @Override
        public void onSpanCreated(String instrumentationName) {}

        @Override
        public void onSpanFinished(String instrumentationName) {}
      };

  /**
   * Increment span created counter.
   *
   * @param instrumentationName The instrumentation that created the span.
   */
  void onSpanCreated(String instrumentationName);

  /**
   * Increment span finished counter.
   *
   * @param instrumentationName The instrumentation that created the span.
   */
  void onSpanFinished(String instrumentationName);
}
