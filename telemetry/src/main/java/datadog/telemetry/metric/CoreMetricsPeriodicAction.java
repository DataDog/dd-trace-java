package datadog.telemetry.metric;

import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class CoreMetricsPeriodicAction extends MetricPeriodicAction {
  @NonNull
  @Override
  public MetricCollector collector() {
    return CoreMetricCollector.getInstance();
  }
}
