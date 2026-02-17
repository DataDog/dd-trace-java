package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.ExecutionsByDuration;
import datadog.trace.civisibility.execution.exit.EarlyExitPolicy;
import java.util.List;

/**
 * Runs a test case N times (N depends on test duration) regardless of success or failure. The
 * execution can also be terminated early if its ExitPolicy evaluates to {@code true}.
 */
public class RunNTimes implements TestExecutionPolicy {

  private final boolean suppressFailures;
  private final List<ExecutionsByDuration> executionsByDuration;
  private int executions;
  private int maxExecutions;
  private int successfulExecutionsSeen;
  private int failedExecutionsSeen;
  private final RetryReason retryReason;
  private TestStatus lastStatus;
  private final EarlyExitPolicy exitPolicy;

  public RunNTimes(
      List<ExecutionsByDuration> executionsByDuration,
      boolean suppressFailures,
      RetryReason retryReason,
      EarlyExitPolicy exitPolicy) {
    this.suppressFailures = suppressFailures;
    this.executionsByDuration = executionsByDuration;
    this.executions = 0;
    this.maxExecutions = getExecutions(0);
    this.successfulExecutionsSeen = 0;
    this.retryReason = retryReason;
    this.exitPolicy = exitPolicy;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    lastStatus = status;
    ++executions;
    if (status != TestStatus.fail) {
      ++successfulExecutionsSeen;
    } else {
      ++failedExecutionsSeen;
    }
    int maxExecutionsForGivenDuration = getExecutions(durationMillis);
    maxExecutions = Math.min(maxExecutions, maxExecutionsForGivenDuration);

    boolean lastExecution = !retriesLeft();
    boolean retry = executions > 1; // first execution is not a retry
    boolean failureSuppressed = status == TestStatus.fail && suppressFailures();
    boolean succeededAllRetries = lastExecution && successfulExecutionsSeen == executions;

    TestStatus finalStatus = null;
    if (lastExecution) {
      // final status will only be "pass" if all retries pass (or the failures were suppressed)
      // also, the `suppressFailures()` call works because its value cannot change between retries
      if (succeededAllRetries || suppressFailures()) {
        finalStatus = TestStatus.pass;
      } else {
        finalStatus = TestStatus.fail;
      }
    }

    return new ExecutionOutcomeImpl(
        failureSuppressed,
        lastExecution,
        lastExecution && failedExecutionsSeen == executions,
        succeededAllRetries,
        retry ? retryReason : null,
        finalStatus);
  }

  private boolean retriesLeft() {
    // skipped tests (either by the framework or DD) should not be retried
    return lastStatus != TestStatus.skip
        && executions < maxExecutions
        && !exitPolicy.evaluate(failedExecutionsSeen != 0, successfulExecutionsSeen != 0);
  }

  @Override
  public boolean applicable() {
    // executions must always be registered, therefore consider it applicable as long as there are
    // retries left
    return retriesLeft();
  }

  @Override
  public boolean suppressFailures() {
    return suppressFailures;
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
