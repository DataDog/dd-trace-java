package datadog.opentelemetry.shim.metrics.export;

import datadog.opentelemetry.shim.metrics.OtelInstrumentDescriptor;

public interface OtelMeterVisitor {
  /** Visits an instrument created by the meter. */
  OtelInstrumentVisitor visitInstrument(OtelInstrumentDescriptor descriptor);
}
