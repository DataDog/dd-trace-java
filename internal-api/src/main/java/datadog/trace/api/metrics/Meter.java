package datadog.trace.api.metrics;

import java.util.List;
import java.util.concurrent.atomic.DoubleAdder;

/** This class describes a meter metric, a synchronous instrument to measure rate of events. */
public class Meter<T extends Number> extends Instrument {
  protected final DoubleAdder count;

  /**
   * Constructor.
   *
   * @param name The metric name.
   * @param common Whether the metric is common ({@code true}) or language specific ({@code false}).
   * @param tags The metric tags.
   */
  protected Meter(String name, boolean common, List<String> tags) {
    super(name, common, tags);
    this.count = new DoubleAdder();
  }

  @Override
  public String getType() {
    return "RATE";
  }

  /** Mark an event occurrence. */
  public void mark() {
    this.count.add(1D);
    this.updated.set(true);
  }

  /**
   * Mark a given number of occurrence of the event.
   *
   * @param amount The number of occurrence of the event.
   */
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
