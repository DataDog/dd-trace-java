package datadog.trace.instrumentation.junit5.retry;

public class NoOpRetryContext implements RetryContext {

  static final RetryContext INSTANCE = new NoOpRetryContext();

  private NoOpRetryContext() {}

  @Override
  public void prepareRetry() {
    // no op
  }

  @Override
  public void executeRetryIfNeeded() {
    // no op
  }
}
