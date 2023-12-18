package datadog.trace.instrumentation.junit5.retry;

public interface RetryContext {

  String RETRY_ATTEMPT_TEST_ID_SEGMENT = "retry-attempt";

  void prepareRetry();

  void executeRetryIfNeeded();
}
