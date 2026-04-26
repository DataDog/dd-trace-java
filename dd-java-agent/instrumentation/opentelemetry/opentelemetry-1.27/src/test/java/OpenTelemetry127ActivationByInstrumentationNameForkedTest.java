import datadog.trace.junit.utils.config.WithConfig;

// Forked test: runs in an isolated JVM with opentelemetry-logs instrumentation enabled
// by integration name. GlobalOpenTelemetry holds static state that must reset between variants.
@WithConfig(key = "integration.opentelemetry-logs.enabled", value = "true")
class OpenTelemetry127ActivationByInstrumentationNameForkedTest
    extends OpenTelemetry127ActivationTest {

  @Override
  boolean shouldBeInjected() {
    return true;
  }
}
