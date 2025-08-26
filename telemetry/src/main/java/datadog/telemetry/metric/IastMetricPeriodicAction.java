package datadog.telemetry.metric;

import datadog.config.telemetry.MetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class IastMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @NonNull
  public MetricCollector collector() {
    return IastMetricCollector.get();
  }
}
