package datadog.trace.core.otlp.metrics;

import datadog.trace.core.otlp.common.OtlpPayload;

final class NoopOtlpMetricsCollector extends OtlpMetricsCollector {
  static final NoopOtlpMetricsCollector INSTANCE = new NoopOtlpMetricsCollector();

  public OtlpPayload collectMetrics() {
    return OtlpPayload.EMPTY;
  }
}
