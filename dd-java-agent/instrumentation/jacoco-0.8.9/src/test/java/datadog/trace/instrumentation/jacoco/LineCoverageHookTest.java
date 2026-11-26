package datadog.trace.instrumentation.jacoco;

import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_LINE_PROBES_ENABLED;
import static java.lang.reflect.Modifier.isPublic;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class LineCoverageHookTest {

  /** Tests share one class loader, so only these modifiers show the agent's loader split works. */
  @Test
  void hookIsReachableFromAnotherClassLoader() throws NoSuchMethodException {
    assertTrue(isPublic(LineCoverageHook.class.getModifiers()));
    assertTrue(isPublic(LineCoverageHook.class.getDeclaredMethod("isEnabled").getModifiers()));
  }

  @Test
  void hookIsOffWithoutLineCoverage() {
    assertFalse(LineCoverageHook.isEnabled());
    assertFalse(new ProbeInserterInstrumentation().isEnabled());
    assertFalse(new ClassInstrumenterInstrumentation().isEnabled());
  }

  @Test
  @WithConfig(key = CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED, value = "true")
  void hookIsOnForJacocoLineCoverage() {
    assertTrue(LineCoverageHook.isEnabled());
    assertTrue(new ProbeInserterInstrumentation().isEnabled());
    assertTrue(new ClassInstrumenterInstrumentation().isEnabled());
  }

  @Test
  @WithConfig(key = CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED, value = "true")
  @WithConfig(key = CIVISIBILITY_CODE_COVERAGE_LINE_PROBES_ENABLED, value = "true")
  void hookIsOffWhenLineProbesCollectTheLines() {
    assertFalse(LineCoverageHook.isEnabled());
    assertFalse(new ProbeInserterInstrumentation().isEnabled());
    assertFalse(new ClassInstrumenterInstrumentation().isEnabled());
  }
}
