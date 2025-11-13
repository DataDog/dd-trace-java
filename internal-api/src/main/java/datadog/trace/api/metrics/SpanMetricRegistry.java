package datadog.trace.api.metrics;

import datadog.trace.api.InstrumenterConfig;

/** This class holds the {@link SpanMetrics} instances. */
@FunctionalInterface
public interface SpanMetricRegistry {
  SpanMetricRegistry NOOP = instrumentationName -> SpanMetrics.NOOP;

  /**
   * Get the span metrics for an instrumentation.
   *
   * @param instrumentationName The instrumentation name to get span metrics.
   * @return The related span metrics instance.
   */
  SpanMetrics get(String instrumentationName);

  /**
   * @return Human-readable summary of the current span metrics.
   */
  default String summary() {
    return "";
  }

  /**
   * Get the registry instance according the telemetry status.
   *
   * @return The registry instance.
   */
  static SpanMetricRegistry getInstance() {
    return InstrumenterConfig.get().isTelemetryEnabled()
        ? SpanMetricRegistryImpl.getInstance()
        : SpanMetricRegistry.NOOP;
  }
}
