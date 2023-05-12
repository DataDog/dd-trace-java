package datadog.trace.api.metrics;

import datadog.trace.api.InstrumenterConfig;
import java.util.Iterator;
import java.util.function.Supplier;

public interface Metrics {

  static Metrics getInstance() {
    return InstrumenterConfig.get().isTelemetryEnabled()
        ? TelemetryMetrics.INSTANCE
        : NoopMetrics.INSTANCE;
  }

  Counter createCounter(String name, boolean common, String... tags);

  <T extends Number> Gauge<T> createGauge(
      String name, Supplier<T> supplier, boolean common, String... tags);

  <T extends Number> Meter<T> createMeter(String name, boolean common, String... tags);

  Iterator<Instrument> updatedInstruments();
}
