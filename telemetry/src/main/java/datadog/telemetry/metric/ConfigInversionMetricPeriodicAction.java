package datadog.telemetry.metric;

import datadog.trace.api.telemetry.ConfigInversionMetricCollectorImpl;
import datadog.trace.api.telemetry.MetricCollector;
import javax.annotation.Nonnull;

public class ConfigInversionMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @Nonnull
  public MetricCollector collector() {
    return ConfigInversionMetricCollectorImpl.getInstance();
  }
}
