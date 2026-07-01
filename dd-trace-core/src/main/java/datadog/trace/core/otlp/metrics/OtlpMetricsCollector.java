package datadog.trace.core.otlp.metrics;

import datadog.trace.core.otlp.common.OtlpPayload;

/** Collects metrics ready for export. */
public abstract class OtlpMetricsCollector {

  /** Collects all metrics recorded since the last collection. */
  public abstract OtlpPayload collectMetrics();
}
