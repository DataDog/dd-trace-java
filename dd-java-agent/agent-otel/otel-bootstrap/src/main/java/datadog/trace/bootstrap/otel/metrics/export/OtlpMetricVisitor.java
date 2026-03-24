package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor;
import datadog.trace.bootstrap.otel.metrics.data.OtlpDataPoint;

/**
 * A visitor to visit a metric in an instrumentation scope.
 *
 * <p>Methods must be called in the following order: ( visitAttribute* visitPoint )*
 */
public interface OtlpMetricVisitor extends OtlpAttributeVisitor {
  /** Visits an attribute of the upcoming data point. */
  void visitAttribute(int type, String key, Object value);

  /** Visits a data point in the metric. */
  void visitPoint(OtlpDataPoint point);
}
