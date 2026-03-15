package datadog.trace.bootstrap.otel.metrics.data;

final class OtelDoubleValue extends OtelAggregator {
  private volatile double value;

  @Override
  void doRecordDouble(double value) {
    this.value = value;
  }

  @Override
  OtelPoint doCollect(boolean reset) {
    return new OtelDoublePoint(value);
  }
}
