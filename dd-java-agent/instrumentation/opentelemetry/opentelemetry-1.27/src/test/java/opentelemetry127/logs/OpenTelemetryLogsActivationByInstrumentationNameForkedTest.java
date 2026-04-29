package opentelemetry127.logs;

import datadog.trace.junit.utils.config.WithConfig;

// Forked test: runs in an isolated JVM with opentelemetry-logs instrumentation enabled
// by integration name. GlobalOpenTelemetry holds static state that must reset between variants.
@WithConfig(key = "integration.opentelemetry-logs.enabled", value = "true")
class OpenTelemetryLogsActivationByInstrumentationNameForkedTest
    extends OpenTelemetryLogsActivationTest {

  @Override
  boolean shouldBeInjected() {
    return true;
  }
}
