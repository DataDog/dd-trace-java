package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.metrics.data.OtelPoint;

public interface OtelInstrumentVisitor {
  /** Visits a data point collected by the instrument. */
  void visitPoint(Object attributes, OtelPoint point);
}
