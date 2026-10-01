package datadog.telemetry.metric;

import datadog.trace.api.telemetry.FlagEvaluationMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import javax.annotation.Nonnull;

public final class FlagEvaluationMetricPeriodicAction extends MetricPeriodicAction {

  @Override
  @Nonnull
  public MetricCollector collector() {
    return FlagEvaluationMetricCollector.get();
  }
}
