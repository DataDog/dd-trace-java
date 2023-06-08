package datadog.telemetry.metric;

import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class IastMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @NonNull
  public MetricCollector collector() {
    return IastMetricCollector.get();
  }
}
