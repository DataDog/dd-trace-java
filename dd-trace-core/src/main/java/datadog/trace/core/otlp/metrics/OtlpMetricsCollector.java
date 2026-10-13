package datadog.trace.core.otlp.metrics;

import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.util.function.Consumer;

/** Collects metrics ready for export. */
public abstract class OtlpMetricsCollector {

  /** Collects all metrics recorded since the last collection. */
  public abstract OtlpPayload collectMetrics();

  /** Collects metrics from {@code registry} over an explicit time window. */
  abstract OtlpPayload collectMetrics(
      Consumer<OtlpMetricsVisitor> registry, long startNanos, long endNanos);
}
