package datadog.trace.api.metrics;

/**
 * Datadog lifecycle controls implemented by the {@code MeterProvider} returned from {@code
 * GlobalOpenTelemetry} when Datadog OpenTelemetry metrics support is enabled.
 */
public interface DatadogMeterProvider {

  /**
   * Performs a final export and stops Datadog's OpenTelemetry metrics export pipeline. Metrics
   * recorded after shutdown are not exported. Repeated calls observe the first result.
   *
   * <p>The operation has no deadline. A timed join bounds only the caller and does not cancel
   * shutdown.
   *
   * @return the shutdown result; an unavailable or disabled pipeline succeeds as a no-op
   */
  CompletableResultCode shutdown();
}
