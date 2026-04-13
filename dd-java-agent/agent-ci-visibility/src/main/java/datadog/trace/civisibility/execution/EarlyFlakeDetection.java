package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.ExecutionAggregation;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.ExecutionsByDuration;
import java.util.List;

/**
 * Execution policy for Early Flake Detection. Runs a new or modified test case multiple times to
 * determine if it is flaky. The number of executions depends on test duration. Stops retrying once
 * flakiness is detected (mixed pass/fail results).
 */
public class EarlyFlakeDetection implements TestExecutionPolicy {

  private final boolean suppressFailures;
  private final List<ExecutionsByDuration> executionsByDuration;
  private int executions;
  private int maxExecutions;
  private ExecutionAggregation results;
  private TestStatus lastStatus;

  public EarlyFlakeDetection(
      List<ExecutionsByDuration> executionsByDuration, boolean suppressFailures) {
    this.suppressFailures = suppressFailures;
    this.executionsByDuration = executionsByDuration;
    this.executions = 0;
    this.maxExecutions = getExecutions(0);
    this.results = ExecutionAggregation.NONE;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    lastStatus = status;
    ++executions;
    results = results.withExecution(status);
    int maxExecutionsForGivenDuration = getExecutions(durationMillis);
    maxExecutions = Math.min(maxExecutions, maxExecutionsForGivenDuration);

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
        failureSuppressed, lastExecution, results, retry ? RetryReason.efd : null, finalStatus);
  }

  private boolean retriesLeft() {
    // skipped tests should not be retried and stop once flakiness is detected
    return lastStatus != TestStatus.skip
        && executions < maxExecutions
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
  public boolean propagateFailure() {
    // used to bypass TestNG's RetryAnalyzer marking `fail` + `pass` test executions as passed
    return !suppressFailures && results == ExecutionAggregation.MIXED;
  }

  private int getExecutions(long durationMillis) {
    for (ExecutionsByDuration e : executionsByDuration) {
      if (durationMillis <= e.getDurationMillis()) {
        return e.getExecutions();
      }
    }
    return 0;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return false;
  }
}
