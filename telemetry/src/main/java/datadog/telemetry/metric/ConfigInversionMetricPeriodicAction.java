package datadog.telemetry.metric;

import datadog.config.telemetry.MetricCollector;
import datadog.trace.api.telemetry.ConfigInversionMetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ConfigInversionMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @NonNull
  public MetricCollector collector() {
    return ConfigInversionMetricCollector.getInstance();
  }
}
