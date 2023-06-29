package datadog.trace.api.metrics;

/** The core metrics related to a span./ */
public interface SpanMetrics {
  SpanMetrics NOOP =
      new SpanMetrics() {
        @Override
        public void onSpanCreated() {}

        @Override
        public void onSpanFinished() {}
      };

  /** Increment span created counter. */
  void onSpanCreated();

  /** Increment span finished counter. */
  void onSpanFinished();
}
