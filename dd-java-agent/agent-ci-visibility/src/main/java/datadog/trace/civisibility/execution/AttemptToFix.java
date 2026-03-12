package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.ExecutionAggregation;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;

/**
 * Execution policy for the Attempt to Fix feature. Runs a test case up to N times. Stops retrying
 * as soon as a failure is observed, since a single failure proves the fix did not work.
 */
public class AttemptToFix implements TestExecutionPolicy {

  private final int maxExecutions;
  private final boolean suppressFailures;
  private int executions;
  private ExecutionAggregation results;
  private TestStatus lastStatus;

  public AttemptToFix(int maxExecutions, boolean suppressFailures) {
    this.maxExecutions = maxExecutions;
    this.suppressFailures = suppressFailures;
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
    boolean failureSuppressed = status == TestStatus.fail && suppressFailures;
    TestStatus finalStatus = null;
    if (lastExecution) {
      if (results == ExecutionAggregation.ONLY_PASSED || suppressFailures) {
        finalStatus = TestStatus.pass;
      } else {
        finalStatus = TestStatus.fail;
      }
    }

    return new ExecutionOutcomeImpl(
        failureSuppressed,
        lastExecution,
        results,
        retry ? RetryReason.attemptToFix : null,
        finalStatus);
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
    return suppressFailures;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return false;
  }
}
