package datadog.trace.bootstrap.otel.metrics.export;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;

public interface OtelMetricsVisitor {
  /** Visits a meter created by the OpenTelemetry API. */
  OtelMeterVisitor visitMeter(OtelInstrumentationScope scope);
}
