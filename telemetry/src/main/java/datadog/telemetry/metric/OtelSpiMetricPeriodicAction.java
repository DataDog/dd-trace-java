package datadog.telemetry.metric;

import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.telemetry.OtelSpiCollector;
import javax.annotation.Nonnull;

public class OtelSpiMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @Nonnull
  public MetricCollector collector() {
    return OtelSpiCollector.getInstance();
  }
}
