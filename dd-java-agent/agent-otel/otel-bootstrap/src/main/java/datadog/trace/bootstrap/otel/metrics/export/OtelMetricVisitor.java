package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.metrics.data.OtelPoint;

/** A visitor to visit a metric in an instrumentation scope. */
public interface OtelMetricVisitor {
  /** Visits a data point in the metric. */
  void visitPoint(Object attributes, OtelPoint point);
}
