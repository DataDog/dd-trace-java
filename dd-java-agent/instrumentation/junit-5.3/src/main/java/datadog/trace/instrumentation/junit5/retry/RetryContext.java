package datadog.trace.instrumentation.junit5.retry;

public interface RetryContext {

  void prepareRetry();

  void executeRetryIfNeeded();
}
