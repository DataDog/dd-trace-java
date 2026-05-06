package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;

final class OtelDoubleValue extends OtelAggregator {
  private volatile double value;

  @Override
  void doRecordDouble(double value) {
    this.value = value;
  }

  @Override
  OtlpDataPoint doCollect(boolean reset) {
    return new OtlpDoublePoint(value);
  }
}
