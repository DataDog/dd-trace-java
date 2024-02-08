package datadog.telemetry.metric;

import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class CiVisibilityMetricPeriodicAction extends MetricPeriodicAction {
  @NonNull
  @Override
  public MetricCollector collector() {
    return CiVisibilityMetricCollector.getInstance();
  }
}
