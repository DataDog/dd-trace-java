package opentelemetry147.metrics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import org.junit.jupiter.api.Test;

abstract class OpenTelemetryMetricsActivationTest extends AbstractInstrumentationTest {

  abstract boolean shouldBeInjected();

  @Test
  void testInstrumentationInjection() {
    Meter meter = GlobalOpenTelemetry.get().getMeterProvider().get("some-instrumentation");
    if (shouldBeInjected()) {
      assertTrue(
          meter.getClass().getName().endsWith(".OtelMeter"),
          "Expected OtelMeter but got: " + meter.getClass().getName());
    } else {
      assertTrue(
          meter.getClass().getName().endsWith(".DefaultMeter"),
          "Expected DefaultMeter but got: " + meter.getClass().getName());
    }
  }
}
