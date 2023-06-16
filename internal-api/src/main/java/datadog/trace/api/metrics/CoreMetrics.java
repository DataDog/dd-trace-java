package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/** The default {@link Metrics} service implementation. */
public final class CoreMetrics implements Metrics {
  static final CoreMetrics INSTANCE = new CoreMetrics();

  private final List<Instrument> instruments;

  private CoreMetrics() {
    this.instruments = new ArrayList<>();
  }

  public static Metrics getInstance() {
    return INSTANCE;
  }

  @Override
  public Counter createCounter(MetricName name, String... tags) {
    Counter counter = new Counter(name, Arrays.asList(tags));
    this.instruments.add(counter);
    return counter;
  }

  @Override
  public <T extends Number> Gauge<T> createGauge(
      MetricName name, Supplier<T> valueSupplier, String... tags) {
    Gauge<T> gauge = new Gauge<T>(name, valueSupplier, Arrays.asList(tags));
    this.instruments.add(gauge);
    return gauge;
  }

  @Override
  public <T extends Number> Meter<T> createMeter(MetricName name, String... tags) {
    Meter<T> meter = new Meter<>(name, Arrays.asList(tags));
    this.instruments.add(meter);
    return meter;
  }

  @Override
  public Iterator<Instrument> updatedInstruments() {
    return this.instruments.stream().filter(instrument -> instrument.updated.get()).iterator();
  }
}
