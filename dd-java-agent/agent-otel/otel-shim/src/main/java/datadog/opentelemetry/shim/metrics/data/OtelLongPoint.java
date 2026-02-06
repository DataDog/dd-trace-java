package datadog.opentelemetry.shim.metrics.data;

public final class OtelLongPoint extends OtelPoint {
  public final long value;

  OtelLongPoint(long value) {
    this.value = value;
  }
}
