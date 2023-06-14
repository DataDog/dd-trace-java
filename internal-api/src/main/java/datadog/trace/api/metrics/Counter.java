package datadog.trace.api.metrics;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public class Counter extends Instrument {
  protected final LongAdder count;

  protected Counter(String name, boolean common, List<String> tags) {
    super(name, common, tags);
    this.count = new LongAdder();
  }

  @Override
  public String getType() {
    return "COUNT";
  }

  public void increment() {
    this.count.increment();
    this.updated.set(true);
  }

  public void increment(long amount) {
    this.count.add(amount);
    this.updated.set(true);
  }

  @Override
  public Number getValue() {
    return this.count;
  }

  @Override
  public void resetValue() {
    this.count.reset();
  }
}
