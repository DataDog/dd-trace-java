package datadog.trace.api.metrics;

import datadog.trace.api.InstrumenterConfig;
import java.util.Iterator;
import java.util.function.Supplier;

/** This interface defines the metric service. */
public interface Metrics {
  /**
   * Get the metric service implementation according the telemetry activation state.
   *
   * @return The metric service implementation.
   */
  static Metrics getInstance() {
    return InstrumenterConfig.get().isTelemetryEnabled()
        ? CoreMetrics.INSTANCE
        : NoopMetrics.INSTANCE;
  }

  /**
   * Create a counter instrument.
   *
   * @param name The metric name.
   * @param common Whether the metric is common ({@code true}) or language specific ({@code false}).
   * @param tags The metric tags.
   */
  Counter createCounter(String name, boolean common, String... tags);

  /**
   * Create a gauge instrument.
   *
   * @param name The metric name.
   * @param valueSupplier The supplier providing instrument value.
   * @param common Whether the metric is common ({@code true}) or language specific ({@code false}).
   * @param tags The metric tags.
   */
  <T extends Number> Gauge<T> createGauge(
      String name, Supplier<T> valueSupplier, boolean common, String... tags);

  /**
   * Create a meter instrument.
   *
   * @param name The metric name.
   * @param common Whether the metric is common ({@code true}) or language specific ({@code false}).
   * @param tags The metric tags.
   */
  <T extends Number> Meter<T> createMeter(String name, boolean common, String... tags);

  /**
   * Get an iterator with updated instruments.
   *
   * @return The updated instrument collection wrapped with an iterator.
   */
  Iterator<Instrument> updatedInstruments();
}
