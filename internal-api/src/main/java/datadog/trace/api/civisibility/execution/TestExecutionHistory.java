package datadog.trace.api.civisibility.execution;

import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

public interface TestExecutionHistory {

  /**
   * @param status result of the execution: pass, fail or skip
   * @param durationMillis duration of current test execution in milliseconds
   */
  ExecutionOutcome registerExecution(TestStatus status, long durationMillis);

  /**
   * @return {@code true} if the test should be instrumented by FTR
   */
  boolean failedTestReplayApplicable();

  interface ExecutionOutcome {
    /**
     * @return {@code true} if this execution failed and the failure was suppressed
     */
    boolean failureSuppressed();

    /**
     * @return {@code true} if this execution is the last one (only for policies that allow multiple
     *     retries)
     */
    boolean lastExecution();

    /**
     * @return {@code true} if the test has failed all retry attempts (only for policies that allow
     *     multiple retries)
     */
    boolean failedAllRetries();

    /**
     * @return {@code true} if the test has succeeded all retry attempts (only for policies that
     *     allow multiple retries)
     */
    boolean succeededAllRetries();

    /**
     * @return retry reason for current test execution ({@code null} if current execution is not a
     *     retry)
     */
    @Nullable
    RetryReason retryReason();
  }
}
