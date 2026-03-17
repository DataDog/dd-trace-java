package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;

/** A visitor to visit metrics produced by an instrumentation scope. */
public interface OtelScopedMetricsVisitor {
  /** Visits a metric in the instrumentation scope. */
  OtelMetricVisitor visitMetric(OtelInstrumentDescriptor descriptor);
}
