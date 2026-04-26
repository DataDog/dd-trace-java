import datadog.trace.junit.utils.config.WithConfig;

// Forked test: runs in an isolated JVM with opentelemetry-logs instrumentation enabled
// by OTel RFC config name. GlobalOpenTelemetry holds static state that must reset between variants.
@WithConfig(key = "logs.otel.enabled", value = "true")
class OpenTelemetry127ActivationByOtelRfcNameForkedTest extends OpenTelemetry127ActivationTest {

  @Override
  boolean shouldBeInjected() {
    return true;
  }
}
