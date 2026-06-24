package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;

/** Always reports the latest value. */
final class OtelLongValue extends OtelAggregator {
  private volatile long value;

  @Override
  void doRecordLong(long value) {
    this.value = value;
  }

  @Override
  OtlpDataPoint doCollect(boolean reset) {
    return new OtlpLongPoint(value);
  }
}
