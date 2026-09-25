package datadog.trace.api.metrics;

import datadog.trace.api.CompletableResultCode;

/**
 * Datadog lifecycle controls implemented by the {@code MeterProvider} returned from {@code
 * GlobalOpenTelemetry} when Datadog OpenTelemetry metrics support is enabled.
 */
public interface DatadogMeterProvider {

  /**
   * Performs a final export and stops Datadog's OpenTelemetry metrics pipeline. Repeated calls
   * observe the first result.
   *
   * <p>A timed join bounds only the caller and does not cancel shutdown.
   *
   * <p>No guarantees are made if this is called from a JVM shutdown hook: the final export may race
   * with other shutdown hooks and never complete.
   *
   * @return the shutdown result; an unavailable or disabled pipeline succeeds as a no-op
   */
  CompletableResultCode shutdown();
}
