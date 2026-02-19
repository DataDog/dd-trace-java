package datadog.opentelemetry.shim.metrics.export;

import datadog.opentelemetry.shim.metrics.data.OtelPoint;
import io.opentelemetry.api.common.Attributes;

public interface OtelInstrumentVisitor {
  /** Visits a data point collected by the instrument. */
  void visitPoint(Attributes attributes, OtelPoint point);
}
