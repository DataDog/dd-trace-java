package datadog.telemetry.metric;

import datadog.trace.api.WafMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class WafMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @NonNull
  public MetricCollector collector() {
    return WafMetricCollector.get();
  }
}
