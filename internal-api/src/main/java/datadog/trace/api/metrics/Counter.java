package datadog.trace.api.metrics;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * This class describes a counter metric, a synchronous instrument with positive integer increment.
 */
public class Counter extends Instrument {
  protected final LongAdder count;

  /**
   * Constructor.
   *
   * @param name The metric name.
   * @param tags The metric tags.
   */
  protected Counter(MetricName name, List<String> tags) {
    super(name, tags);
    this.count = new LongAdder();
  }

  @Override
  public String getType() {
    return "COUNT";
  }

  /** Increment the counter. */
  public void increment() {
    this.count.increment();
    this.updated.set(true);
  }

  /**
   * Increment the counter by a given amount.
   *
   * @param amount The amount to increment the counter.
   */
  public void increment(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("Increment must be positive: " + amount);
    }
    this.count.add(amount);
    this.updated.set(true);
  }

  @Override
  public Number getValue() {
    return this.count;
  }

  @Override
  protected void resetValue() {
    this.count.reset();
  }
}
