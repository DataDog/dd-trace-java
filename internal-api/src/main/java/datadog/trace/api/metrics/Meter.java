package datadog.trace.api.metrics;

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
    this.updated.set(true);
  }

  public void mark(T amount) {
    this.count.add(amount.doubleValue());
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
