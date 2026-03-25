package datadog.trace.bootstrap.otel.metrics.data;

public final class OtlpLongPoint extends OtlpDataPoint {
  public final long value;

  OtlpLongPoint(long value) {
    this.value = value;
  }
}
