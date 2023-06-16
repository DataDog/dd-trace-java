package datadog.trace.api.metrics;

import java.util.List;
import java.util.function.Supplier;

/** This class describes a gauge metric, an asynchronous instrument where values will be pulled. */
public class Gauge<T extends Number> extends Instrument {
  private final Supplier<T> supplier;

  /**
   * Constructor.
   *
   * @param name The metric name.
   * @param valueSupplier The supplier providing instrument value.
   * @param tags The metric tags.
   */
  public Gauge(MetricName name, Supplier<T> valueSupplier, List<String> tags) {
    super(name, tags);
    this.supplier = valueSupplier;
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
  protected void resetValue() {
    // No value to clear
  }
}
