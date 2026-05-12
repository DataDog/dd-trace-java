package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.ExecutionAggregation;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;

/**
 * Execution policy for the Attempt to Fix feature. Runs a test case up to N times. Stops retrying
 * as soon as a failure is observed, since a single failure proves the fix did not work.
 *
 * <p>Failures are never suppressed, even when the test is also quarantined or disabled — the whole
 * point of an attempt-to-fix is to verify that the fix worked, so a failing run must surface as a
 * failing run.
 */
public class AttemptToFix implements TestExecutionPolicy {

  private final int maxExecutions;
  private int executions;
  private ExecutionAggregation results;
  private TestStatus lastStatus;

  public AttemptToFix(int maxExecutions) {
    this.maxExecutions = maxExecutions;
    this.executions = 0;
    this.results = ExecutionAggregation.NONE;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    lastStatus = status;
    ++executions;
    results = results.withExecution(status);

    boolean lastExecution = !retriesLeft();
    boolean retry = executions > 1;
    TestStatus finalStatus = null;
    if (lastExecution) {
      finalStatus = results == ExecutionAggregation.ONLY_PASSED ? TestStatus.pass : TestStatus.fail;
    }

    return new ExecutionOutcomeImpl(
        false, lastExecution, results, retry ? RetryReason.attemptToFix : null, finalStatus);
  }

  private boolean retriesLeft() {
    // stop retrying if the test was skipped, max executions reached,
    // or a failure was observed (the fix didn't work)
    return lastStatus != TestStatus.skip
        && executions < maxExecutions
        && results != ExecutionAggregation.ONLY_FAILED
        && results != ExecutionAggregation.MIXED;
  }

  @Override
  public boolean applicable() {
    return retriesLeft();
  }

  @Override
  public boolean suppressFailures() {
    return false;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return false;
  }
}
