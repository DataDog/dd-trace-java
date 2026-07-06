package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;

/** Reports the delta value since the last reset. */
final class OtelDoubleDelta extends OtelAggregator {
  private volatile double value;
  private double lastValue;

  @Override
  void doRecordDouble(double value) {
    this.value = value;
  }

  @Override
  OtlpDataPoint doCollect(boolean reset) {
    double collectedValue = value;
    double delta = collectedValue - lastValue;
    if (reset) {
      lastValue = collectedValue;
    }
    return new OtlpDoublePoint(delta);
  }
}
