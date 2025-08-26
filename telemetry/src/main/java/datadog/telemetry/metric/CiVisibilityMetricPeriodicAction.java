package datadog.telemetry.metric;

import datadog.config.telemetry.MetricCollector;
import datadog.trace.api.civisibility.InstrumentationBridge;
import edu.umd.cs.findbugs.annotations.NonNull;

public class CiVisibilityMetricPeriodicAction extends MetricPeriodicAction {
  @NonNull
  @Override
  public MetricCollector collector() {
    return InstrumentationBridge.getMetricCollector();
  }
}
