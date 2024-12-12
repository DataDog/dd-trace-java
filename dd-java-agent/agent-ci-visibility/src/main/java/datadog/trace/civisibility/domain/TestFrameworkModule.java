package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Test module abstraction that is used by test framework instrumentations (e.g. JUnit, TestNG) */
public interface TestFrameworkModule {
  TestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation);

  /**
   * Checks if a given test is "new" or not. A test is considered "new" if the backend has no
   * information about it.
   *
   * @param test Test to be checked
   * @return {@code true} if the test is new, {@code false} if it is an existing test <b>or if the
   *     list of known tests is not available</b>.
   */
  boolean isNew(TestIdentifier test);

  boolean isFlaky(TestIdentifier test);

  /**
   * Checks if a given test should be skipped with Intelligent Test Runner or not
   *
   * @param test Test to be checked
   * @return {@code true} if the test can be skipped, {@code false} otherwise
   */
  boolean shouldBeSkipped(TestIdentifier test);

  /**
   * Checks if a given test can be skipped with Intelligent Test Runner or not. If the test is
   * considered skippable, the count of skippable tests is incremented.
   *
   * @param test Test to be checked
   * @return {@code true} if the test can be skipped, {@code false} otherwise
   */
  boolean skip(TestIdentifier test);

  @Nonnull
  TestRetryPolicy retryPolicy(TestIdentifier test);

  void end(Long startTime);
}
