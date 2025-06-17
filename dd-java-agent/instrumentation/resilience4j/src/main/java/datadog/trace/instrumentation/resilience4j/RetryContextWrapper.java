package datadog.trace.instrumentation.resilience4j;

import io.github.resilience4j.retry.Retry;

public class RetryContextWrapper<T> implements Retry.Context<T> {
  private final Retry.Context<T> delegate;
  private final DDContext ddContext;

  public RetryContextWrapper(Retry.Context<T> delegate, DDContext ddContext) {
    this.delegate = delegate;
    this.ddContext = ddContext;
  }

  @Override
  public void onComplete() {
    delegate.onComplete();
    ddContext.closeScope();
    ddContext.finishSpan(null);
  }

  @Override
  public boolean onResult(T result) {
    return delegate.onResult(result);
  }

  @Override
  public void onError(Exception e) throws Exception {
    delegate.onError(e);
  }

  @Override
  public void onRuntimeError(RuntimeException e) {
    delegate.onRuntimeError(e);
  }
}
