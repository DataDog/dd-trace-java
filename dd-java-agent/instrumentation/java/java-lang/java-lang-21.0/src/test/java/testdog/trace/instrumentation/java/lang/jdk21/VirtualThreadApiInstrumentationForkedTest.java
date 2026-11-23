package testdog.trace.instrumentation.java.lang.jdk21;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the {@link VirtualThreadApiInstrumentationTest} cases with the legacy context manager
 * disabled, so {@code VirtualThreadState} takes the swap path instead of seed-once. Forked because
 * the legacy-context-manager choice is captured once per JVM.
 */
@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class VirtualThreadApiInstrumentationForkedTest extends VirtualThreadApiInstrumentationTest {
  @DisplayName("test per-mount path is selected for the new context manager")
  @Test
  void testPerMountPathSelected() {
    assertTrue(VirtualThreadState.usePerMountContext());
  }
}
