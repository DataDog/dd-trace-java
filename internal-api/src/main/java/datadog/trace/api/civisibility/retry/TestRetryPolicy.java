package datadog.trace.api.civisibility.retry;

public interface TestRetryPolicy {
  boolean retryPossible();

  boolean suppressFailures();

  boolean retry(boolean successful);
}
