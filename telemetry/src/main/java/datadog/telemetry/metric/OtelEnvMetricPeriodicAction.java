package datadog.telemetry.metric;

import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.telemetry.OtelEnvMetricCollectorImpl;
import edu.umd.cs.findbugs.annotations.NonNull;

public class OtelEnvMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @NonNull
  public MetricCollector collector() {
    return OtelEnvMetricCollectorImpl.getInstance();
  }
}
