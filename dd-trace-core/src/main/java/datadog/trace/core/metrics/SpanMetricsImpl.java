package datadog.trace.core.metrics;

import static datadog.trace.api.metrics.MetricName.named;

import datadog.trace.api.metrics.Counter;
import datadog.trace.api.metrics.Metrics;
import java.util.HashMap;
import java.util.Map;

public class SpanMetricsImpl implements SpanMetrics {
  private static final String METRIC_NAMESPACE = "tracers";
  private final Metrics metrics;
  private final Map<String, Counter> spanCreatedCounter;
  private final Map<String, Counter> spanFinishedCounter;

  public SpanMetricsImpl(Metrics metrics) {
    this.metrics = metrics;
    this.spanCreatedCounter = new HashMap<>();
    this.spanFinishedCounter = new HashMap<>();
  }

  @Override
  public void onSpanCreated(String instrumentationName) {
    Counter counter = this.spanCreatedCounter.get(instrumentationName);
    if (counter == null) {
      counter =
          metrics.createCounter(named(METRIC_NAMESPACE, true, "span_created"), instrumentationName);
      this.spanCreatedCounter.put(instrumentationName, counter);
    }
    counter.increment();
  }

  @Override
  public void onSpanFinished(String instrumentationName) {
    Counter counter = this.spanFinishedCounter.get(instrumentationName);
    if (counter == null) {
      counter =
          this.metrics.createCounter(
              named(METRIC_NAMESPACE, true, "span_finished"), instrumentationName);
      this.spanFinishedCounter.put(instrumentationName, counter);
    }
    counter.increment();
  }
}
