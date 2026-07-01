package datadog.trace.bootstrap.otlp.metrics;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;

/** A visitor to visit OpenTelemetry metrics. */
public interface OtlpMetricsVisitor {
  /** Visits metrics produced by an instrumentation scope. */
  OtlpScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope);
}
