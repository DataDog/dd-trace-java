package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/** Retries a test case if it failed, up to a maximum number of times. */
public class RetryUntilSuccessful implements TestExecutionPolicy {

  private final int maxExecutions;
  private int executions;

  /** Total execution counter that is shared by all retry policies */
  private final AtomicInteger totalExecutions;

  public RetryUntilSuccessful(int maxExecutions, AtomicInteger totalExecutions) {
    this.maxExecutions = maxExecutions;
    this.totalExecutions = totalExecutions;
    this.executions = 0;
  }

  @Override
  public boolean applicable() {
    // the last execution is not altered by the policy
    // (no retries, no exceptions suppressing)
    return executions < maxExecutions - 1;
  }

  @Override
  public boolean suppressFailures() {
    // if this isn't the last execution,
    // possible failures should be suppressed
    return applicable();
  }

  @Override
  public boolean retry(boolean successful, long durationMillis) {
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
}
