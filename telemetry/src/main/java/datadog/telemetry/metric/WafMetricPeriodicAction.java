package datadog.telemetry.metric;

import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.telemetry.WafMetricCollector;
import javax.annotation.Nonnull;

public class WafMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @Nonnull
  public MetricCollector collector() {
    return WafMetricCollector.get();
  }
}
