package datadog.opentelemetry.shim.metrics.export;

import datadog.opentelemetry.shim.OtelInstrumentationScope;

public interface OtelMetricsVisitor {
  /** Visits a meter created by the OpenTelemetry API. */
  OtelMeterVisitor visitMeter(OtelInstrumentationScope scope);
}
