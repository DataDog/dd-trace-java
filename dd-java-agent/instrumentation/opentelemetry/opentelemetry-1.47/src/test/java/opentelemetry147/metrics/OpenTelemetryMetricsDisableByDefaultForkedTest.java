package opentelemetry147.metrics;

// Forked test: runs in an isolated JVM with no opentelemetry-metrics config override,
// verifying that the instrumentation is disabled by default.
// GlobalOpenTelemetry holds static state that must reset between variants.
class OpenTelemetryMetricsDisableByDefaultForkedTest extends OpenTelemetryMetricsActivationTest {

  @Override
  boolean shouldBeInjected() {
    return false;
  }
}
