package datadog.trace.api.metrics;

import java.util.ArrayList;
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
    addCurrentValue();
  }

  public void increment(long amount) {
    this.count.add(amount);
    addCurrentValue();
  }

  protected void addCurrentValue() {
    List<Number> value = new ArrayList<>(2);
    value.add(System.currentTimeMillis());
    value.add(this.count.sum());
    this.values.add(value);
    this.updated.set(true);
  }
}
