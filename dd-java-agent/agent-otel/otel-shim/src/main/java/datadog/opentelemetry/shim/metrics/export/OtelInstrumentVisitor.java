package datadog.opentelemetry.shim.metrics.export;

import datadog.opentelemetry.shim.metrics.data.OtelPoint;

public interface OtelInstrumentVisitor {
  /** Visits a data point collected by the instrument. */
  void visitPoint(Object attributes, OtelPoint point);
}
