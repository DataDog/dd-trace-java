package datadog.trace.instrumentation.resilience4j;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.Map;

public final class RetryWrapper implements Retry {
  private final Retry original;
  private final DDContext ddContext;

  public RetryWrapper(Retry original, DDContext ddContext) {
    this.original = original;
    this.ddContext = ddContext;
  }

  @Override
  public String getName() {
    return original.getName();
  }

  @Override
  public <T> Context<T> context() {
    ddContext.openScope();
    return new RetryContextWrapper<>(original.context(), ddContext);
  }

  @Override
  public <T> AsyncContext<T> asyncContext() {
    return new RetryAsyncContextWrapper<>(original.asyncContext(), ddContext);
  }

  @Override
  public RetryConfig getRetryConfig() {
    return original.getRetryConfig();
  }

  @Override
  public Map<String, String> getTags() {
    return original.getTags();
  }

  @Override
  public EventPublisher getEventPublisher() {
    return original.getEventPublisher();
  }

  @Override
  public Metrics getMetrics() {
    return original.getMetrics();
  }
}
