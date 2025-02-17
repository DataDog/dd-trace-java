package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/** Retries a test case if it failed, up to a maximum number of times. */
public class RetryUntilSuccessful implements TestExecutionPolicy {

  private final int maxExecutions;
  private final boolean suppressFailures;
  private int executions;
  private boolean successfulExecutionSeen;

  /** Total execution counter that is shared by all retry policies */
  private final AtomicInteger totalExecutions;

  public RetryUntilSuccessful(
      int maxExecutions, boolean suppressFailures, AtomicInteger totalExecutions) {
    this.maxExecutions = maxExecutions;
    this.suppressFailures = suppressFailures;
    this.totalExecutions = totalExecutions;
    this.executions = 0;
  }

  @Override
  public boolean applicable() {
    return !currentExecutionIsLast() || suppressFailures;
  }

  @Override
  public boolean suppressFailures() {
    // do not suppress failures for last execution
    // (unless flag to suppress all failures is set)
    return !currentExecutionIsLast() || suppressFailures;
  }

  private boolean currentExecutionIsLast() {
    return executions == maxExecutions - 1;
  }

  @Override
  public boolean retry(boolean successful, long durationMillis) {
    successfulExecutionSeen |= successful;
    if (!successful && ++executions < maxExecutions) {
      totalExecutions.incrementAndGet();
      return true;
    } else {
      return false;
    }
  }

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return currentExecutionIsRetry() ? RetryReason.atr : null;
  }

  private boolean currentExecutionIsRetry() {
    return executions > 0;
  }

  @Override
  public boolean hasFailedAllRetries() {
    return currentExecutionIsLast() && !successfulExecutionSeen;
  }

  @Override
  public boolean hasSucceededAllRetries() {
    return false;
  }
}
