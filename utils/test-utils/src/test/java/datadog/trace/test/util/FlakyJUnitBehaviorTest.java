package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

class FlakyJUnitBehaviorTest {
  private static final String RUN_FLAKY_TESTS = "run.flaky.tests";

  @Test
  void selectsTestsForEachFlakyMode() {
    assertSummary(executeInMode(null), 3, 3, 0);
    assertSummary(executeInMode("false"), 3, 2, 1);
    assertSummary(executeInMode("true"), 1, 1, 0);
  }

  private static TestExecutionSummary executeInMode(String mode) {
    String previous = System.getProperty(RUN_FLAKY_TESTS);
    try {
      if (mode == null) {
        System.clearProperty(RUN_FLAKY_TESTS);
      } else {
        System.setProperty(RUN_FLAKY_TESTS, mode);
      }

      LauncherDiscoveryRequest discoveryRequest =
          request().selectors(selectClass(FlakyFixture.class)).build();
      LauncherConfig launcherConfig =
          LauncherConfig.builder()
              .enableLauncherSessionListenerAutoRegistration(false)
              .enableLauncherDiscoveryListenerAutoRegistration(false)
              .enableTestExecutionListenerAutoRegistration(false)
              .build();
      Launcher launcher = LauncherFactory.create(launcherConfig);
      SummaryGeneratingListener listener = new SummaryGeneratingListener();
      launcher.execute(discoveryRequest, listener);
      return listener.getSummary();
    } finally {
      if (previous == null) {
        System.clearProperty(RUN_FLAKY_TESTS);
      } else {
        System.setProperty(RUN_FLAKY_TESTS, previous);
      }
    }
  }

  private static void assertSummary(
      TestExecutionSummary summary, long found, long succeeded, long skipped) {
    assertEquals(found, summary.getTestsFoundCount());
    assertEquals(succeeded, summary.getTestsSucceededCount());
    assertEquals(skipped, summary.getTestsSkippedCount());
    assertEquals(0, summary.getTestsFailedCount());
  }

  static class FlakyFixture {
    @Test
    void regularTest() {}

    @Test
    @Flaky(conditionMethod = "datadog.trace.test.util.InheritedFlakyCondition#isFlaky")
    void flakyTest() {}

    @Test
    @Flaky(conditionMethod = "datadog.trace.test.util.NonFlakyCondition#isFlaky")
    void conditionDoesNotMatch() {}
  }
}

class InheritedFlakyCondition extends FlakyCondition {}

class FlakyCondition {
  static boolean isFlaky() {
    return true;
  }
}

class NonFlakyCondition {
  static boolean isFlaky() {
    return false;
  }
}
