package datadog.opentelemetry.shim.metrics.data;

final class OtelDoubleValue extends OtelAggregator {
  private volatile double value;

  @Override
  protected void doRecordDouble(double value) {
    this.value = value;
  }

  @Override
  protected OtelPoint doCollect(boolean reset) {
    return new OtelDoublePoint(value);
  }
}
