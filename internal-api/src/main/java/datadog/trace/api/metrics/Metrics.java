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
   * @param tags The metric tags.
   */
  Counter createCounter(MetricName name, String... tags);

  /**
   * Create a gauge instrument.
   *
   * @param name The metric name.
   * @param valueSupplier The supplier providing instrument value.
   * @param tags The metric tags.
   */
  <T extends Number> Gauge<T> createGauge(MetricName name, Supplier<T> valueSupplier, String... tags);

  /**
   * Create a meter instrument.
   *
   * @param name The metric name.
   * @param tags The metric tags.
   */
  <T extends Number> Meter<T> createMeter(MetricName name, String... tags);

  /**
   * Get an iterator with updated instruments.
   *
   * @return The updated instrument collection wrapped with an iterator.
   */
  Iterator<Instrument> updatedInstruments();
}
