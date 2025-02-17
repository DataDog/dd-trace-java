package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.ExecutionsByDuration;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Runs a test case N times (N depends on test duration) regardless of success or failure. */
public class RunNTimes implements TestExecutionPolicy {

  private final boolean suppressFailures;
  private int executions;
  private final List<ExecutionsByDuration> executionsByDuration;
  private int maxExecutions;
  private int successfulExecutionsSeen;
  private final RetryReason retryReason;

  public RunNTimes(
      List<ExecutionsByDuration> executionsByDuration,
      boolean suppressFailures,
      RetryReason retryReason) {
    this.suppressFailures = suppressFailures;
    this.executions = 0;
    this.executionsByDuration = executionsByDuration;
    this.maxExecutions = getExecutions(0);
    this.retryReason = retryReason;
  }

  @Override
  public boolean applicable() {
    return !currentExecutionIsLast() || suppressFailures();
  }

  private boolean currentExecutionIsLast() {
    return executions == maxExecutions - 1;
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
  public boolean retry(boolean successful, long durationMillis) {
    if (successful) {
      ++successfulExecutionsSeen;
    }
    int maxExecutionsForGivenDuration = getExecutions(durationMillis);
    maxExecutions = Math.min(maxExecutions, maxExecutionsForGivenDuration);
    return ++executions < maxExecutions;
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
    return currentExecutionIsLast() && successfulExecutionsSeen == 0;
  }

  @Override
  public boolean hasSucceededAllRetries() {
    return currentExecutionIsLast() && successfulExecutionsSeen == maxExecutions - 1;
  }
}
