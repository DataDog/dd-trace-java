package datadog.telemetry.metric;

import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.telemetry.OtlpTelemetry;
import javax.annotation.Nonnull;

public class OtlpTelemetryPeriodicAction extends MetricPeriodicAction {

  @Override
  @Nonnull
  public MetricCollector collector() {
    return OtlpTelemetry.getInstance();
  }
}
