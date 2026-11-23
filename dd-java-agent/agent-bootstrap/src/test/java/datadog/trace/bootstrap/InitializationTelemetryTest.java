package datadog.trace.bootstrap;

import static datadog.trace.bootstrap.InitializationTelemetry.noOpInstance;
import static datadog.trace.bootstrap.InitializationTelemetry.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class InitializationTelemetryTest {

  @Test
  void telemetryWrapperNullCase() {
    assertEquals(noOpInstance(), proxy(null));
  }

  @Test
  void telemetryWrapperWrapBootstrap() {
    // TODO: Figure out how to test the wrapper fully
    assertNotEquals(noOpInstance(), proxy(new Object()));
  }
}
