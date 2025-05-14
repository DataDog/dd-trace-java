package datadog.trace.api.civisibility.execution;

import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

public interface TestExecutionHistory {

  /**
   * @param status result of the execution: pass, fail or skip
   * @param durationMillis duration of current test execution in milliseconds
   */
  void registerExecution(TestStatus status, long durationMillis);

  /**
   * @return {@code true} if the last execution registered was the last one (only for policies that
   *     allow multiple retries)
   */
  boolean wasLastExecution();

  /**
   * @return retry reason for current test execution ({@code null} if current execution is not a
   *     retry)
   */
  @Nullable
  RetryReason currentExecutionRetryReason();

  /**
   * @return {@code true} if the test has failed all retry attempts (only for policies that allow
   *     multiple retries)
   */
  boolean hasFailedAllRetries();

  /**
   * @return {@code true} if the test has succeeded all retry attempts (only for policies that allow
   *     multiple retries)
   */
  boolean hasSucceededAllRetries();
}
