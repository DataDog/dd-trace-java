package datadog.telemetry.metric;

import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;

public class LLMObsMetricPeriodicAction extends MetricPeriodicAction {
  @NonNull
  @Override
  public MetricCollector collector() {
    return LLMObsMetricCollector.get();
  }
}
