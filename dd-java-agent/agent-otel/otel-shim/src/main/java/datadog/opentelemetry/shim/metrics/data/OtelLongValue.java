package datadog.opentelemetry.shim.metrics.data;

final class OtelLongValue extends OtelAggregator {
  private volatile long value;

  @Override
  protected void doRecordLong(long value) {
    this.value = value;
  }

  @Override
  protected OtelPoint doCollect(boolean reset) {
    return new OtelLongPoint(value);
  }
}
