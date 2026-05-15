package datadog.trace.bootstrap.otlp.metrics;

import datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor;

/**
 * A visitor to visit a metric in an instrumentation scope.
 *
 * <p>Methods must be called in the following order: ( visitAttribute* visitDataPoint )*
 */
public interface OtlpMetricVisitor extends OtlpAttributeVisitor {
  /** Visits an attribute of the upcoming data point. */
  void visitAttribute(int type, String key, Object value);

  /** Visits a data point in the metric. */
  void visitDataPoint(OtlpDataPoint point);
}
