package datadog.trace.core.metrics;

import datadog.trace.api.metrics.Counter;
import datadog.trace.api.metrics.Metrics;
import java.util.HashMap;
import java.util.Map;

public class SpanMetricsImpl implements SpanMetrics {
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
      counter = metrics.createCounter("span_created", true, instrumentationName);
      this.spanCreatedCounter.put(instrumentationName, counter);
    }
    counter.increment();
  }

  @Override
  public void onSpanFinished(String instrumentationName) {
    Counter counter = this.spanFinishedCounter.get(instrumentationName);
    if (counter == null) {
      counter = this.metrics.createCounter("span_finished", true, instrumentationName);
      this.spanFinishedCounter.put(instrumentationName, counter);
    }
    counter.increment();
  }
}
