package datadog.telemetry.metric;

import datadog.trace.api.telemetry.ConfigInversionMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ConfigInversionMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @NonNull
  public MetricCollector collector() {
    return ConfigInversionMetricCollector.getInstance();
  }
}
