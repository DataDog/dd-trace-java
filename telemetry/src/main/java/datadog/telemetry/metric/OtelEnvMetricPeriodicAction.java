package datadog.telemetry.metric;

import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.telemetry.OtelEnvMetricCollectorImpl;
import javax.annotation.Nonnull;

public class OtelEnvMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @Nonnull
  public MetricCollector collector() {
    return OtelEnvMetricCollectorImpl.getInstance();
  }
}
