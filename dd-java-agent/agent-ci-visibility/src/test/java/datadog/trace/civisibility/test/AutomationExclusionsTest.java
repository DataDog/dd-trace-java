package datadog.trace.civisibility.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.config.TestManagementSettings;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class AutomationExclusionsTest {
  @NoAutoRetry
  @PreventFailureSuppression
  public void protectedTest() {}

  @Test
  void protectedFailureRemainsBlockingAndAssignedPropertiesRemainVisible() throws Exception {
    TestIdentifier id = new TestIdentifier("suite", "name", null);
    ExecutionSettings settings = mock(ExecutionSettings.class);
    TestManagementSettings management = mock(TestManagementSettings.class);
    when(management.isEnabled()).thenReturn(true);
    when(settings.getTestManagementSettings()).thenReturn(management);
    when(settings.isDisabled(id.toFQN())).thenReturn(true);
    when(settings.isQuarantined(id.toFQN())).thenReturn(true);
    when(settings.isAttemptToFix(id.toFQN())).thenReturn(true);
    when(settings.isFlakyTestRetriesEnabled()).thenReturn(true);
    when(settings.getEarlyFlakeDetectionSettings()).thenReturn(EarlyFlakeDetectionSettings.DEFAULT);
    ExecutionStrategy strategy =
        new ExecutionStrategy(
            mock(Config.class),
            settings,
            mock(SourcePathResolver.class),
            mock(LinesResolver.class));
    TestSourceData source = new TestSourceData(getClass(), getClass().getMethod("protectedTest"));
    assertNull(strategy.skipReason(id, source, Collections.emptyList()));
    assertFalse(strategy.executionPolicy(id, source, Collections.emptyList()).suppressFailures());
    assertTrue(
        strategy
            .executionPolicy(id, source, Collections.emptyList())
            .registerExecution(TestStatus.fail, 0)
            .lastExecution());
    assertTrue(strategy.isDisabled(id));
    assertTrue(strategy.isQuarantined(id));
    assertTrue(strategy.isAttemptToFix(id));
    // TIA still applies after the excluded attempt-to-fix assignment is removed from policy.
    when(settings.isTestSkippingEnabled()).thenReturn(true);
    when(settings.getSkippableTests())
        .thenReturn(Collections.singletonMap(id, new TestMetadata(false)));
    assertEquals(SkipReason.ITR, strategy.skipReason(id, source, Collections.emptyList()));
    // Without declarations the existing attempt-to-fix policy is restored.
    assertNull(strategy.skipReason(id, TestSourceData.UNKNOWN, Collections.emptyList()));
  }

  @Test
  void noRetryAloneRetainsQuarantineAndDisabledSelection() {
    TestIdentifier id = new TestIdentifier("suite", "name", null);
    ExecutionSettings settings = mock(ExecutionSettings.class);
    TestManagementSettings management = mock(TestManagementSettings.class);
    when(management.isEnabled()).thenReturn(true);
    when(settings.getTestManagementSettings()).thenReturn(management);
    when(settings.isDisabled(id.toFQN())).thenReturn(true);
    when(settings.isQuarantined(id.toFQN())).thenReturn(true);
    when(settings.isAttemptToFix(id.toFQN())).thenReturn(true);
    ExecutionStrategy strategy =
        new ExecutionStrategy(
            mock(Config.class),
            settings,
            mock(SourcePathResolver.class),
            mock(LinesResolver.class));
    assertEquals(
        SkipReason.DISABLED,
        strategy.skipReason(
            id, TestSourceData.UNKNOWN, Collections.singletonList("datadog_no_auto_retry")));
    assertTrue(
        strategy
            .executionPolicy(
                id, TestSourceData.UNKNOWN, Collections.singletonList("datadog_no_auto_retry"))
            .suppressFailures());
  }
}
