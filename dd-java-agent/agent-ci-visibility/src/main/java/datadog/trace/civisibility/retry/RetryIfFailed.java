package datadog.trace.civisibility.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;

public class RetryIfFailed implements TestRetryPolicy {

  private int remainingRetries;

  public RetryIfFailed(int totalExecutions) {
    this.remainingRetries = totalExecutions - 1;
  }

  @Override
  public boolean retryPossible() {
    return remainingRetries > 0;
  }

  @Override
  public boolean suppressFailures() {
    return true;
  }

  @Override
  public boolean retry(boolean successful) {
    return !successful && remainingRetries-- > 0;
  }
}
