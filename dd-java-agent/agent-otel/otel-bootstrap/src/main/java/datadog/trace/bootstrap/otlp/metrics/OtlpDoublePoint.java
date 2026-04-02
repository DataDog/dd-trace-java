package datadog.trace.bootstrap.otlp.metrics;

public final class OtlpDoublePoint extends OtlpDataPoint {
  public final double value;

  public OtlpDoublePoint(double value) {
    this.value = value;
  }
}
