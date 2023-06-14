package datadog.trace.api.metrics;

import java.util.List;
import java.util.function.Supplier;

public class Gauge<T extends Number> extends Instrument {
  private final Supplier<T> supplier;

  public Gauge(String name, Supplier<T> supplier, boolean common, List<String> tags) {
    super(name, common, tags);
    this.supplier = supplier;
    this.updated.set(true);
  }

  @Override
  public String getType() {
    return "GAUGE";
  }

  @Override
  public Number getValue() {
    return this.supplier.get();
  }

  @Override
  public void reset() {
    // Do not clear updated flag
  }

  @Override
  public void resetValue() {
    // No value to clear
  }
}
