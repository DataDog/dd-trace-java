package datadog.trace.civisibility.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.civisibility.execution.TestExecutionTracker.ExecutionOutcome;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.DynamicAutoTestRetrySettings;
import datadog.trace.civisibility.config.ExecutionsByDuration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DynamicAutoTestRetryTest {

  @ParameterizedTest(name = "duration={0}ms -> retries={1}")
  @MethodSource("backendBuckets")
  void usesBackendRetryBudgets(long durationMillis, int expectedRetries) {
    DynamicAutoTestRetry policy = newPolicy(settings(null));

    assertEquals(expectedRetries, executeUntilFinished(policy, durationMillis));
  }

  static Stream<Arguments> backendBuckets() {
    return Stream.of(
        arguments(1_000L, 10), arguments(6_000L, 2), arguments(31_000L, 4), arguments(301_000L, 1));
  }

  @ParameterizedTest(name = "duration={0}ms -> retries={1}")
  @MethodSource("customBuckets")
  void usesCustomRetryBudgets(long durationMillis, int expectedRetries) {
    DynamicAutoTestRetry policy = newPolicy(settings(Arrays.asList(4, 1, 1, 1, 1)));

    assertEquals(expectedRetries, executeUntilFinished(policy, durationMillis));
  }

  static Stream<Arguments> customBuckets() {
    return Stream.of(
        arguments(1_000L, 4), arguments(6_000L, 1), arguments(31_000L, 1), arguments(301_000L, 1));
  }

  @Test
  void stopsAfterFirstPass() {
    AtomicInteger totalRetries = new AtomicInteger();
    DynamicAutoTestRetry policy =
        new DynamicAutoTestRetry(settings(Arrays.asList(5, 1, 1, 1, 1)), false, totalRetries);

    policy.registerExecution(TestStatus.fail, 1_000);
    assertTrue(policy.applicable());

    policy.registerExecution(TestStatus.pass, 1_000);
    assertFalse(policy.applicable());
    assertEquals(1, totalRetries.get());
  }

  @Test
  void usesInitialAttemptDuration() {
    DynamicAutoTestRetry policy = newPolicy(settings(null));

    policy.registerExecution(TestStatus.fail, 1_000);
    assertTrue(policy.applicable());

    for (int retry = 1; retry <= 10; retry++) {
      ExecutionOutcome outcome = policy.registerExecution(TestStatus.fail, 600_000);
      assertEquals(retry < 10, policy.applicable());
      assertEquals(retry == 10, outcome.lastExecution());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesFailureSuppression(boolean suppressFailures) {
    AtomicInteger totalRetries = new AtomicInteger();
    DynamicAutoTestRetry policy =
        new DynamicAutoTestRetry(settings(null), suppressFailures, totalRetries);

    assertTrue(policy.applicable());
    assertTrue(policy.suppressFailures());
    assertTrue(policy.failedTestReplayApplicable());

    ExecutionOutcome initial = policy.registerExecution(TestStatus.fail, 600_000);
    assertTrue(initial.failureSuppressed());
    assertFalse(initial.lastExecution());
    assertTrue(policy.applicable());
    assertEquals(suppressFailures, policy.suppressFailures());
    assertEquals(0, totalRetries.get());

    ExecutionOutcome retry = policy.registerExecution(TestStatus.fail, 1_000);
    assertTrue(retry.lastExecution());
    assertEquals(suppressFailures, retry.failureSuppressed());
    assertEquals(suppressFailures ? TestStatus.pass : TestStatus.fail, retry.finalStatus());
    assertEquals(RetryReason.atr, retry.retryReason());
    assertFalse(policy.applicable());
    assertEquals(1, totalRetries.get());
  }

  @Test
  void stopsAfterInitialPass() {
    AtomicInteger totalRetries = new AtomicInteger();
    DynamicAutoTestRetry policy = new DynamicAutoTestRetry(settings(null), false, totalRetries);

    ExecutionOutcome outcome = policy.registerExecution(TestStatus.pass, 1_000);

    assertTrue(outcome.lastExecution());
    assertFalse(outcome.failureSuppressed());
    assertEquals(TestStatus.pass, outcome.finalStatus());
    assertFalse(policy.applicable());
    assertEquals(0, totalRetries.get());
  }

  private static DynamicAutoTestRetry newPolicy(DynamicAutoTestRetrySettings settings) {
    return new DynamicAutoTestRetry(settings, false, new AtomicInteger());
  }

  private static int executeUntilFinished(DynamicAutoTestRetry policy, long durationMillis) {
    int retries = 0;
    while (true) {
      policy.registerExecution(TestStatus.fail, durationMillis);
      if (!policy.applicable()) {
        return retries;
      }
      retries++;
    }
  }

  private static DynamicAutoTestRetrySettings settings(List<Integer> customBuckets) {
    List<ExecutionsByDuration> backendRetries =
        Arrays.asList(
            new ExecutionsByDuration(5_000, 10),
            new ExecutionsByDuration(10_000, 2),
            new ExecutionsByDuration(30_000, 3),
            new ExecutionsByDuration(300_000, 4));
    return DynamicAutoTestRetrySettings.create(true, customBuckets, backendRetries);
  }
}
