package datadog.telemetry.metric;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class CiVisibilityMetricPeriodicAction extends MetricPeriodicAction {
  @NonNull
  @Override
  public MetricCollector collector() {
    return InstrumentationBridge.getMetricCollector();
  }
}
