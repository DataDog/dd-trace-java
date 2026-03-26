package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;

/** A visitor to visit OpenTelemetry metrics. */
public interface OtlpMetricsVisitor {
  /** Visits metrics produced by an instrumentation scope. */
  OtlpScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope);
}
