package datadog.telemetry.metric;

import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import javax.annotation.Nonnull;

public class IastMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @Nonnull
  public MetricCollector collector() {
    return IastMetricCollector.get();
  }
}
