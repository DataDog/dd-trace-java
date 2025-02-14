package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import org.jetbrains.annotations.Nullable;

/** Runs a test case N fixed times regardless of success or failure. */
public class RunNFixedTimes implements TestExecutionPolicy {

  private final boolean suppressFailures;
  private int executions;
  public int maxExecutions;
  private int successfulExecutionsSeen;

  public RunNFixedTimes(int maxExecutions, boolean suppressFailures) {
    this.suppressFailures = suppressFailures;
    this.executions = 0;
    this.maxExecutions = maxExecutions;
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

  @Override
  public boolean retry(boolean successful, long durationMillis) {
    if (successful) {
      ++successfulExecutionsSeen;
    }
    return ++executions < maxExecutions;
  }

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return currentExecutionIsRetry() ? RetryReason.attemptToFix : null;
  }

  public boolean currentExecutionIsRetry() {
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
