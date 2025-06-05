package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/** Retries a test case if it failed, up to a maximum number of times. */
public class RetryUntilSuccessful implements TestExecutionPolicy {

  private final int maxExecutions;
  private final boolean suppressFailures;
  private int executions;
  private boolean successfulExecutionSeen;

  /** Total retry counter that is shared by all retry until successful policies (currently ATR) */
  private final AtomicInteger totalRetryCount;

  public RetryUntilSuccessful(
      int maxExecutions, boolean suppressFailures, AtomicInteger totalRetryCount) {
    this.maxExecutions = maxExecutions;
    this.suppressFailures = suppressFailures;
    this.totalRetryCount = totalRetryCount;
    this.executions = 0;
  }

  @Override
  public void registerExecution(TestStatus status, long durationMillis) {
    ++executions;
    successfulExecutionSeen |= (status != TestStatus.fail);
    if (executions > 1) {
      totalRetryCount.incrementAndGet();
    }
  }

  @Override
  public boolean wasLastExecution() {
    return successfulExecutionSeen || executions == maxExecutions;
  }

  private boolean currentExecutionIsLast() {
    return executions == maxExecutions - 1;
  }

  @Override
  public boolean applicable() {
    // executions must always be registered, therefore consider it applicable as long as there are
    // retries left
    return !wasLastExecution();
  }

  @Override
  public boolean suppressFailures() {
    // do not suppress failures for last execution
    // (unless flag to suppress all failures is set)
    return !currentExecutionIsLast() || suppressFailures;
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
    return wasLastExecution() && !successfulExecutionSeen;
  }

  @Override
  public boolean hasSucceededAllRetries() {
    return false;
  }
}
