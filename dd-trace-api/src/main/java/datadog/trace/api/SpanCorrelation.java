package datadog.trace.api;

public interface SpanCorrelation {
  SpanCorrelation EMPTY =
      new SpanCorrelation() {
        @Override
        public DDId getTraceId() {
          return DDId.ZERO;
        }

        @Override
        public DDId getSpanId() {
          return DDId.ZERO;
        }
      };

  interface Provider {
    SpanCorrelation getSpanCorrelation();
  }

  DDId getTraceId();

  DDId getSpanId();
}
