package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;

/** A visitor to visit OpenTelemetry metrics. */
public interface OtelMetricsVisitor {
  /** Visits metrics produced by an instrumentation scope. */
  OtelScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope);
}
