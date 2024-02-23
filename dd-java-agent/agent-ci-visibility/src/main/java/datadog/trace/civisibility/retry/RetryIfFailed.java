package datadog.trace.civisibility.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;

/** Retries a test case if it failed, up to a maximum number of times. */
public class RetryIfFailed implements TestRetryPolicy {

  private final int maxExecutions;
  private int executions;

  public RetryIfFailed(int maxExecutions) {
    this.maxExecutions = maxExecutions;
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
  public boolean retry(boolean successful, long duration) {
    return !successful && ++executions < maxExecutions;
  }

  @Override
  public boolean currentExecutionIsRetry() {
    return executions > 0;
  }
}
