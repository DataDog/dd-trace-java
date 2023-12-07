package datadog.trace.api.civisibility.retry;

public interface TestRetryPolicy {
  boolean retryPossible();

  boolean retry(boolean successful);
}
