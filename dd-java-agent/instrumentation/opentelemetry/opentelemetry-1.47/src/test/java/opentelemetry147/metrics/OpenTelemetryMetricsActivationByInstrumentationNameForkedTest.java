package opentelemetry147.metrics;

import datadog.trace.junit.utils.config.WithConfig;

// Forked test: runs in an isolated JVM with opentelemetry-metrics instrumentation enabled
// by integration name. GlobalOpenTelemetry holds static state that must reset between variants.
@WithConfig(key = "integration.opentelemetry-metrics.enabled", value = "true")
class OpenTelemetryMetricsActivationByInstrumentationNameForkedTest
    extends OpenTelemetryMetricsActivationTest {

  @Override
  boolean shouldBeInjected() {
    return true;
  }
}
