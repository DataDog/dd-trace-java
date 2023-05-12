package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class Gauge<T extends Number> extends Instrument {
  private final Supplier<T> supplier;
  private final ArrayList<Number> value;

  public Gauge(String name, Supplier<T> supplier, boolean common, List<String> tags) {
    super(name, common, tags);
    this.supplier = supplier;
    this.updated.set(true);
    this.value = new ArrayList<>(2);
    this.values.add(this.value);
  }

  @Override
  public String getType() {
    return "GAUGE";
  }

  @Override
  public List<List<Number>> getValues() {
    this.value.clear();
    this.value.add(System.currentTimeMillis());
    this.value.add(this.supplier.get());
    return this.values;
  }

  @Override
  public void reset() {
    // Do not clear updated flag nor values collection
  }
}
