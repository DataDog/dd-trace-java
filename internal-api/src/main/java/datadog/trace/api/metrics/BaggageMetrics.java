package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Metrics for baggage propagation operations. */
public class BaggageMetrics {
  private static final BaggageMetrics INSTANCE = new BaggageMetrics();
  private final AtomicLong extractedCounter = new AtomicLong(0);
  private final AtomicLong injectedCounter = new AtomicLong(0);
  private final AtomicLong malformedCounter = new AtomicLong(0);
  private final AtomicLong truncatedByteCounter = new AtomicLong(0);
  private final AtomicLong truncatedItemCounter = new AtomicLong(0);
  private final Collection<TaggedCounter> taggedCounters;

  public static BaggageMetrics getInstance() {
    return INSTANCE;
  }

  private BaggageMetrics() {
    List<TaggedCounter> counters = new ArrayList<>(5);
    counters.add(
        new TaggedCounter(
            "context_header_style.extracted", this.extractedCounter, "header_style:baggage"));
    counters.add(
        new TaggedCounter(
            "context_header_style.injected", this.injectedCounter, "header_style:baggage"));
    counters.add(
        new TaggedCounter(
            "context_header_style.malformed", this.malformedCounter, "header_style:baggage"));
    counters.add(
        new TaggedCounter(
            "context_header_style.truncated",
            this.truncatedByteCounter,
            "truncation_reason:baggage_byte_count_exceeded"));
    counters.add(
        new TaggedCounter(
            "context_header_style.truncated",
            this.truncatedItemCounter,
            "truncation_reason:baggage_item_count_exceeded"));
    this.taggedCounters = Collections.unmodifiableList(counters);
  }

  public void onBaggageExtracted() {
    this.extractedCounter.incrementAndGet();
  }

  public void onBaggageInjected() {
    this.injectedCounter.incrementAndGet();
  }

  public void onBaggageMalformed() {
    this.malformedCounter.incrementAndGet();
  }

  public void onBaggageTruncatedByByteLimit() {
    this.truncatedByteCounter.incrementAndGet();
  }

  public void onBaggageTruncatedByItemLimit() {
    this.truncatedItemCounter.incrementAndGet();
  }

  public Collection<TaggedCounter> getTaggedCounters() {
    return this.taggedCounters;
  }

  public static class TaggedCounter implements CoreCounter {
    private final String name;
    private final AtomicLong counter;
    private final String tag;
    private long previousCount;

    public TaggedCounter(String name, AtomicLong counter, String tag) {
      this.name = name;
      this.counter = counter;
      this.tag = tag;
    }

    @Override
    public String getName() {
      return this.name;
    }

    public String getTag() {
      return this.tag;
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
