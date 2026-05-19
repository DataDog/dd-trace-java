package datadog.telemetry.metric;

import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.api.telemetry.OtelSpiCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class OtelSpiMetricPeriodicAction extends MetricPeriodicAction {
  @Override
  @NonNull
  public MetricCollector collector() {
    return OtelSpiCollector.getInstance();
  }
}
