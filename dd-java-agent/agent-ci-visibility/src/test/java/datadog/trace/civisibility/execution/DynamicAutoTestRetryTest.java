package datadog.trace.civisibility.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.civisibility.execution.TestStatus;
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

    policy.registerExecution(TestStatus.fail, 600_000);
    assertTrue(policy.applicable());
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
