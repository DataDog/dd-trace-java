package datadog.trace.api.metrics;

import static datadog.trace.api.metrics.MetricName.named;
import static java.util.Collections.emptyList;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

class NoopMetrics implements Metrics {
  static final Metrics INSTANCE = new NoopMetrics();

  @Override
  public Counter createCounter(MetricName name, String... tags) {
    return NoopCounter.INSTANCE;
  }

  @Override
  public <T extends Number> Gauge<T> createGauge(MetricName name, Supplier<T> valueSupplier, String... tags) {
    return new NoopGauge<>();
  }

  @Override
  public <T extends Number> Meter<T> createMeter(MetricName name, String... tags) {
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
      super(named("noop", false, "noop"), emptyList());
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
      super(named("noop", false, "noop"), null, emptyList());
    }

    @Override
    public Number getValue() {
      return 0;
    }
  }

  private static class NoopMeter<T extends Number> extends Meter<T> {
    private NoopMeter() {
      super(named("noop", false, "noop"), emptyList());
    }

    @Override
    public void mark() {}

    @Override
    public void mark(T amount) {}
  }
}
