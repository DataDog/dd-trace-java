package datadog.trace.instrumentation.resilience4j;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.Map;

public class RetryWithContext implements Retry {
  private final Retry original;

  public RetryWithContext(Retry original, DDContext ddContext) {
    this.original = original;
  }

  @Override
  public String getName() {
    return original.getName();
  }

  @Override
  public <T> Context<T> context() {
    // TODO wrap to hold DD context
    return original.context();
  }

  @Override
  public <T> AsyncContext<T> asyncContext() {
    // TODO wrap to hold DD context
    return original.asyncContext();
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
