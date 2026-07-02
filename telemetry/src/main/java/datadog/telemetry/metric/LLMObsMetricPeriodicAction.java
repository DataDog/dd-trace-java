package datadog.telemetry.metric;

import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import javax.annotation.Nonnull;

public class LLMObsMetricPeriodicAction extends MetricPeriodicAction {
  @Nonnull
  @Override
  public MetricCollector collector() {
    return LLMObsMetricCollector.get();
  }
}
