package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.ExecutionsByDuration;
import java.util.List;
import javax.annotation.Nullable;

/** Runs a test case N times (N depends on test duration) regardless of success or failure. */
public class RunNTimes implements TestExecutionPolicy {

  private final boolean suppressFailures;
  private final List<ExecutionsByDuration> executionsByDuration;
  private int executions;
  private int maxExecutions;
  private int successfulExecutionsSeen;
  private final RetryReason retryReason;
  private TestStatus lastStatus;

  public RunNTimes(
      List<ExecutionsByDuration> executionsByDuration,
      boolean suppressFailures,
      RetryReason retryReason) {
    this.suppressFailures = suppressFailures;
    this.executionsByDuration = executionsByDuration;
    this.executions = 0;
    this.maxExecutions = getExecutions(0);
    this.successfulExecutionsSeen = 0;
    this.retryReason = retryReason;
  }

  @Override
  public void registerExecution(TestStatus status, long durationMillis) {
    lastStatus = status;
    ++executions;
    if (status != TestStatus.fail) {
      ++successfulExecutionsSeen;
    }
    int maxExecutionsForGivenDuration = getExecutions(durationMillis);
    maxExecutions = Math.min(maxExecutions, maxExecutionsForGivenDuration);
  }

  @Override
  public boolean wasLastExecution() {
    // skipped tests (either by the framework or DD) should not be retried
    return lastStatus == TestStatus.skip || executions >= maxExecutions;
  }

  @Override
  public boolean applicable() {
    // executions must always be registered, therefore consider it applicable as long as there are
    // retries left
    return !wasLastExecution();
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

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return currentExecutionIsRetry() ? retryReason : null;
  }

  private boolean currentExecutionIsRetry() {
    return executions > 0;
  }

  @Override
  public boolean hasFailedAllRetries() {
    return wasLastExecution() && successfulExecutionsSeen == 0;
  }

  @Override
  public boolean hasSucceededAllRetries() {
    return wasLastExecution() && successfulExecutionsSeen == executions;
  }
}
