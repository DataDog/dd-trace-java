package datadog.telemetry.metric;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.telemetry.MetricCollector;
import javax.annotation.Nonnull;

public class CiVisibilityMetricPeriodicAction extends MetricPeriodicAction {
  @Nonnull
  @Override
  public MetricCollector collector() {
    return InstrumentationBridge.getMetricCollector();
  }
}
