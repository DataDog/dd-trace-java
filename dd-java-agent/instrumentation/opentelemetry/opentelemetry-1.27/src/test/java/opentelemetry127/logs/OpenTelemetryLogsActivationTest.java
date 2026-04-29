package opentelemetry127.logs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.logs.Logger;
import org.junit.jupiter.api.Test;

abstract class OpenTelemetryLogsActivationTest extends AbstractInstrumentationTest {

  abstract boolean shouldBeInjected();

  @Test
  void testInstrumentationInjection() {
    Logger logger = GlobalOpenTelemetry.get().getLogsBridge().get("some-instrumentation");
    if (shouldBeInjected()) {
      assertTrue(
          logger.getClass().getName().endsWith(".OtelLogger"),
          "Expected OtelLogger but got: " + logger.getClass().getName());
    } else {
      assertTrue(
          logger.getClass().getName().endsWith(".DefaultLogger"),
          "Expected DefaultLogger but got: " + logger.getClass().getName());
    }
  }
}
