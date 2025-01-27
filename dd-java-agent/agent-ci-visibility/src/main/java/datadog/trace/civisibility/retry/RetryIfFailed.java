package datadog.trace.civisibility.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/** Retries a test case if it failed, up to a maximum number of times. */
public class RetryIfFailed implements TestRetryPolicy {

  private final int maxExecutions;
  private int executions;

  /** Total execution counter that is shared by all retry policies */
  private final AtomicInteger totalExecutions;

  public RetryIfFailed(int maxExecutions, AtomicInteger totalExecutions) {
    this.maxExecutions = maxExecutions;
    this.totalExecutions = totalExecutions;
    this.executions = 0;
  }

  @Override
  public boolean retriesLeft() {
    return executions < maxExecutions - 1;
  }

  @Override
  public boolean suppressFailures() {
    return true;
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

  @Override
  public boolean currentExecutionIsRetry() {
    return executions > 0;
  }

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return currentExecutionIsRetry() ? RetryReason.atr : null;
  }
}
