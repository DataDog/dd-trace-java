package datadog.telemetry.metric;

import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import javax.annotation.Nonnull;

public class CoreMetricsPeriodicAction extends MetricPeriodicAction {
  @Nonnull
  @Override
  public MetricCollector collector() {
    return CoreMetricCollector.getInstance();
  }
}
