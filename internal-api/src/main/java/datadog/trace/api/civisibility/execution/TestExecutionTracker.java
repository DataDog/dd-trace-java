package datadog.trace.api.civisibility.execution;

import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

/**
 * Stateful tracker for test executions within a single test case. Records execution results and
 * produces {@link ExecutionOutcome} objects that describe what happened and what tags/metadata to
 * attach to the test span.
 *
 * <p>This is the narrower view of a {@link TestExecutionPolicy}: tracing listeners receive a {@code
 * TestExecutionTracker} to record results, while execution instrumentations receive the full {@link
 * TestExecutionPolicy} which adds decision methods ({@link TestExecutionPolicy#applicable()},
 * {@link TestExecutionPolicy#suppressFailures()}) used to drive retry loops.
 *
 * @see TestExecutionPolicy
 */
public interface TestExecutionTracker {

  /**
   * Records the result of a test execution and returns the outcome.
   *
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
     * @return Aggregated results across all executions so far
     */
    ExecutionAggregation aggregation();

    /**
     * @return retry reason for current test execution ({@code null} if current execution is not a
     *     retry)
     */
    @Nullable
    RetryReason retryReason();

    /**
     * @return Final status of the test as seen by the testing framework. Only applicable if {@code
     *     lastExecution()} is true.
     */
    @Nullable
    TestStatus finalStatus();
  }
}
