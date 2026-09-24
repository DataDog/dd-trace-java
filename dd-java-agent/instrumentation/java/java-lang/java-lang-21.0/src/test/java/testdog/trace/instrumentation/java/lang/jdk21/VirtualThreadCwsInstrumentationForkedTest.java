package testdog.trace.instrumentation.java.lang.jdk21;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that CWS retains scope-listener notifications on every virtual-thread mount. */
@WithConfig(key = "cws.enabled", value = "true")
class VirtualThreadCwsInstrumentationForkedTest extends VirtualThreadApiInstrumentationTest {
  @DisplayName("test per-mount path is selected for CWS")
  @Test
  void testPerMountPathSelected() {
    assertTrue(VirtualThreadState.usePerMountContext());
  }
}
