package datadog.telemetry.metric;

import datadog.config.telemetry.MetricCollector;
import datadog.trace.api.telemetry.OtelEnvMetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class OtelEnvMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @NonNull
  public MetricCollector collector() {
    return OtelEnvMetricCollector.getInstance();
  }
}
