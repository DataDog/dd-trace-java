package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;

public interface OtelMeterVisitor {
  /** Visits an instrument created by the meter. */
  OtelInstrumentVisitor visitInstrument(OtelInstrumentDescriptor descriptor);
}
