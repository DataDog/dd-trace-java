package datadog.trace.api.civisibility.retry;

public interface TestRetryPolicy {
  boolean retriesLeft();

  boolean suppressFailures();

  boolean retry(boolean successful, long duration);

  boolean currentExecutionIsRetry();
}
