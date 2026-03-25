package datadog.trace.bootstrap.otel.metrics.data;

public final class OtlpDoublePoint extends OtlpDataPoint {
  public final double value;

  OtlpDoublePoint(double value) {
    this.value = value;
  }
}
