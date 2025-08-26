package datadog.telemetry.metric;

import datadog.config.telemetry.MetricCollector;
import datadog.trace.api.telemetry.WafMetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class WafMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @NonNull
  public MetricCollector collector() {
    return WafMetricCollector.get();
  }
}
