package datadog.trace.bootstrap.otel.metrics.data;

/** Common behaviour shared across all aggregators. */
abstract class OtelAggregator {
  private volatile boolean empty = true;

  final boolean isEmpty() {
    return empty;
  }

  final void recordDouble(double value) {
    doRecordDouble(value);
    empty = false;
  }

  final void recordLong(long value) {
    doRecordLong(value);
    empty = false;
  }

  final OtlpDataPoint collect() {
    return doCollect(false);
  }

  final OtlpDataPoint collectAndReset() {
    OtlpDataPoint point = doCollect(true);
    empty = true;
    return point;
  }

  void doRecordDouble(double value) {
    throw new UnsupportedOperationException();
  }

  void doRecordLong(long value) {
    throw new UnsupportedOperationException();
  }

  abstract OtlpDataPoint doCollect(boolean reset);
}
