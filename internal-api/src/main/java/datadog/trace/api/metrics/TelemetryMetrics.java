package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public final class TelemetryMetrics implements Metrics {
  static final TelemetryMetrics INSTANCE = new TelemetryMetrics();

  private final List<Instrument> instruments;

  private TelemetryMetrics() {
    this.instruments = new ArrayList<>();
  }

  public static Metrics getInstance() {
    return INSTANCE;
  }

  @Override
  public Counter createCounter(String name, boolean common, String... tags) {
    Counter counter = new Counter(name, common, Arrays.asList(tags));
    this.instruments.add(counter);
    return counter;
  }

  @Override
  public <T extends Number> Gauge<T> createGauge(
      String name, Supplier<T> supplier, boolean common, String... tags) {
    Gauge<T> gauge = new Gauge<T>(name, supplier, common, Arrays.asList(tags));
    this.instruments.add(gauge);
    return gauge;
  }

  @Override
  public <T extends Number> Meter<T> createMeter(String name, boolean common, String... tags) {
    Meter<T> meter = new Meter<>(name, common, Arrays.asList(tags));
    this.instruments.add(meter);
    return meter;
  }

  @Override
  public Iterator<Instrument> updatedInstruments() {
    return this.instruments.stream().filter(instrument -> instrument.updated.get()).iterator();
  }
}
