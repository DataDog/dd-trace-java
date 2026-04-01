package datadog.trace.bootstrap.otlp.metrics;

import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;

/** A visitor to visit metrics produced by an instrumentation scope. */
public interface OtlpScopedMetricsVisitor {
  /** Visits a metric in the instrumentation scope. */
  OtlpMetricVisitor visitMetric(OtelInstrumentDescriptor descriptor);
}
