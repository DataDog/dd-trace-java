package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;

/** Reports the delta value since the last reset. */
final class OtelLongDelta extends OtelAggregator {
  private volatile long value;
  private long lastValue;

  @Override
  void doRecordLong(long value) {
    this.value = value;
  }

  @Override
  OtlpDataPoint doCollect(boolean reset) {
    long collectedValue = value;
    long delta = collectedValue - lastValue;
    if (reset) {
      lastValue = collectedValue;
    }
    return new OtlpLongPoint(delta);
  }
}
