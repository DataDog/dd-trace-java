package datadog.trace.instrumentation.resilience4j;

import io.github.resilience4j.retry.Retry;

public final class RetryAsyncContextWrapper<T> implements Retry.AsyncContext<T> {
  private final Retry.AsyncContext<T> delegate;
  private final DDContext ddContext;

  public RetryAsyncContextWrapper(Retry.AsyncContext<T> delegate, DDContext ddContext) {
    this.delegate = delegate;
    this.ddContext = ddContext;
  }

  @Override
  public void onComplete() {
    ddContext.finishSpan(null);
    delegate.onComplete();
  }

  @Override
  public long onError(Throwable throwable) {
    long delay = delegate.onError(throwable);
    return delay;
  }

  @Override
  public long onResult(T result) {
    long delay = delegate.onResult(result);
    return delay;
  }
}
