package datadog.trace.core.otlp.metrics;

import datadog.trace.core.otlp.common.OtlpPayload;

/** Collects metrics ready for export. */
public abstract class OtlpMetricsCollector {
  public abstract OtlpPayload collectMetrics();
}
