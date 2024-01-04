package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** The default implementation of {@link SpanMetrics} based on atomic counters. */
public class SpanMetricsImpl implements SpanMetrics {
  private final String instrumentationName;
  private final AtomicLong spanCreatedCounter;
  private final AtomicLong spanFinishedCounter;
  private final Collection<CoreCounter> coreCounters;

  public SpanMetricsImpl(String instrumentationName) {
    this.instrumentationName = instrumentationName;
    this.spanCreatedCounter = new AtomicLong(0);
    this.spanFinishedCounter = new AtomicLong(0);
    List<CoreCounter> coreCounters = new ArrayList<>(2);
    coreCounters.add(new SpanCounter("spans_created", this.spanCreatedCounter));
    coreCounters.add(new SpanCounter("spans_finished", this.spanFinishedCounter));
    this.coreCounters = Collections.unmodifiableList(coreCounters);
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

  public Collection<CoreCounter> getCounters() {
    return this.coreCounters;
  }

  private static class SpanCounter implements CoreCounter {
    private final String name;
    private final AtomicLong counter;
    private long previousCount;

    private SpanCounter(String name, AtomicLong counter) {
      this.name = name;
      this.counter = counter;
    }

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public long getValue() {
      return counter.get();
    }

    @Override
    public long getValueAndReset() {
      long count = counter.get();
      long delta = count - previousCount;
      previousCount = count;
      return delta;
    }
  }
}
