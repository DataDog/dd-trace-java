package datadog.trace.api.metrics;

import static java.util.Collections.emptyList;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

class NoopMetrics implements Metrics {
  static final Metrics INSTANCE = new NoopMetrics();

  @Override
  public Counter createCounter(String name, boolean common, String... tags) {
    return NoopCounter.INSTANCE;
  }

  @Override
  public <T extends Number> Gauge<T> createGauge(
      String name, Supplier<T> supplier, boolean common, String... tags) {
    return new NoopGauge<>();
  }

  @Override
  public <T extends Number> Meter<T> createMeter(String name, boolean common, String... tags) {
    return new NoopMeter<>();
  }

  @Override
  public Iterator<Instrument> updatedInstruments() {
    return EmptyIterator.INSTANCE;
  }

  private static class EmptyIterator implements Iterator<Instrument> {
    private static final EmptyIterator INSTANCE = new EmptyIterator();

    @Override
    public boolean hasNext() {
      return false;
    }

    @Override
    public Instrument next() {
      throw new NoSuchElementException("No updated instruments with noop metrics");
    }
  }

  private static class NoopCounter extends Counter {
    private static final NoopCounter INSTANCE = new NoopCounter();

    private NoopCounter() {
      super("noop", true, emptyList());
    }

    @Override
    public void increment() {
      // Do nothing
    }

    @Override
    public void increment(long amount) {
      // Do nothing
    }
  }

  private static class NoopGauge<T extends Number> extends Gauge<T> {
    private NoopGauge() {
      super("noop", null, true, emptyList());
    }

    @Override
    public List<List<Number>> getValues() {
      // Do not call supplier
      return this.values;
    }
  }

  private static class NoopMeter<T extends Number> extends Meter<T> {
    private NoopMeter() {
      super("noop", true, emptyList());
    }
  }
}
