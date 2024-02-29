package datadog.trace.civisibility.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;

public class NeverRetry implements TestRetryPolicy {

  public static final TestRetryPolicy INSTANCE = new NeverRetry();

  private NeverRetry() {}

  @Override
  public boolean retriesLeft() {
    return false;
  }

  @Override
  public boolean suppressFailures() {
    return false;
  }

  @Override
  public boolean retry(boolean successful, long duration) {
    return false;
  }

  @Override
  public boolean currentExecutionIsRetry() {
    return false;
  }
}
