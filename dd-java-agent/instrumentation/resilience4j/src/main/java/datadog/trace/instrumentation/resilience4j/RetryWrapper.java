package datadog.trace.instrumentation.resilience4j;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.Map;

public final class RetryWrapper implements Retry {
  private final Retry delegate;
  private final DDContext ddContext;

  public RetryWrapper(Retry delegate, DDContext ddContext) {
    this.delegate = delegate;
    this.ddContext = ddContext;
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public <T> Context<T> context() {
    ddContext.openScope();
    return new RetryContextWrapper<>(delegate.context(), ddContext);
  }

  @Override
  public <T> AsyncContext<T> asyncContext() {
    return new RetryAsyncContextWrapper<>(delegate.asyncContext(), ddContext);
  }

  @Override
  public RetryConfig getRetryConfig() {
    return delegate.getRetryConfig();
  }

  @Override
  public Map<String, String> getTags() {
    return delegate.getTags();
  }

  @Override
  public EventPublisher getEventPublisher() {
    return delegate.getEventPublisher();
  }

  @Override
  public Metrics getMetrics() {
    return delegate.getMetrics();
  }
}
