package datadog.telemetry.metric;

import datadog.trace.api.telemetry.AppSecMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class AppSecEnabledMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @NonNull
  public MetricCollector collector() {
    return AppSecMetricCollector.get();
  }
}
