package opentelemetry147.metrics;

import datadog.trace.test.junit.utils.config.WithConfig;

// Forked test: runs in an isolated JVM with opentelemetry-metrics instrumentation enabled
// by OTel RFC config name. GlobalOpenTelemetry holds static state that must reset between variants.
@WithConfig(key = "metrics.otel.enabled", value = "true")
class OpenTelemetryMetricsActivationByOtelRfcNameForkedTest
    extends OpenTelemetryMetricsActivationTest {

  @Override
  boolean shouldBeInjected() {
    return true;
  }
}
