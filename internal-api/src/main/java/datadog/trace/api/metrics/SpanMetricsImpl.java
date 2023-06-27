package datadog.trace.api.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** The default implementation of {@link SpanMetrics} based on atomic counters. */
public class SpanMetricsImpl implements SpanMetrics {
  private final String instrumentationName;
  private final AtomicLong spanCreatedCounter;
  private final AtomicLong spanFinishedCounter;

  public SpanMetricsImpl(String instrumentationName) {
    this.instrumentationName = instrumentationName;
    this.spanCreatedCounter = new AtomicLong(0);
    this.spanFinishedCounter = new AtomicLong(0);
  }

  @Override
  public void onSpanCreated() {
    this.spanCreatedCounter.incrementAndGet();
  }

  @Override
  public void onSpanFinished() {
    this.spanFinishedCounter.incrementAndGet();
  }

  public String getInstrumentationName() {
    return this.instrumentationName;
  }

  public Map<String, Long> getAndResetCounters() {
    Map<String, Long> counters = new HashMap<>();
    counters.put("span_created", this.spanCreatedCounter.getAndSet(0));
    counters.put("span_finished", this.spanFinishedCounter.getAndSet(0));
    return counters;
  }
}
