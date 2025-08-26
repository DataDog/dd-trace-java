package datadog.telemetry.metric;

import datadog.config.telemetry.MetricCollector;
import datadog.trace.api.telemetry.CoreMetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class CoreMetricsPeriodicAction extends MetricPeriodicAction {
  @NonNull
  @Override
  public MetricCollector collector() {
    return CoreMetricCollector.getInstance();
  }
}
