package datadog.trace.bootstrap.otlp.metrics;

public final class OtlpLongPoint extends OtlpDataPoint {
  public final long value;

  public OtlpLongPoint(long value) {
    this.value = value;
  }
}
