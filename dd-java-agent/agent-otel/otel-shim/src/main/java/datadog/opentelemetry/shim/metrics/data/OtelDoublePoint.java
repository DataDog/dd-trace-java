package datadog.opentelemetry.shim.metrics.data;

public final class OtelDoublePoint extends OtelPoint {
  public final double value;

  OtelDoublePoint(double value) {
    this.value = value;
  }
}
