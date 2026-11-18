package datadog.trace.api.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A named, single-tag counter drained as a telemetry {@code count} metric.
 *
 * <p>The counter can be incremented from any thread. {@link #getValueAndReset()} is only called
 * from the draining thread.
 */
public final class TaggedCounter implements CoreCounter {
  private final String name;
  private final String tag;
  private final AtomicLong counter = new AtomicLong();
  private long previousCount;

  TaggedCounter(String name, String tag) {
    this.name = name;
    this.tag = tag;
  }

  void add(long count) {
    this.counter.addAndGet(count);
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
    return this.counter.get();
  }

  @Override
  public long getValueAndReset() {
    long count = this.counter.get();
    long delta = count - this.previousCount;
    this.previousCount = count;
    return delta;
  }
}
