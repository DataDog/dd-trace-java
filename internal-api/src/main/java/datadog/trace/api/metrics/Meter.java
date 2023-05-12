package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.DoubleAdder;

public class Meter<T extends Number> extends Instrument {
  protected final DoubleAdder count;

  protected Meter(String name, boolean common, List<String> tags) {
    super(name, common, tags);
    this.count = new DoubleAdder();
  }

  @Override
  public String getType() {
    return "RATE";
  }

  public void mark() {
    this.count.add(1D);
    addCurrentValue();
  }

  public void mark(T amount) {
    this.count.add(amount.doubleValue());
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
