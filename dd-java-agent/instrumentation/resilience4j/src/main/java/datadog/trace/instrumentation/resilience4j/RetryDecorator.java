package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.github.resilience4j.retry.Retry;

public final class RetryDecorator extends AbstractResilience4jDecorator<Retry> {
  public static final RetryDecorator DECORATE = new RetryDecorator();

  private RetryDecorator() {
    super();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"resilience4j.retry"};
  }

  @Override
  protected void decorate(AgentScope scope, Retry data) {
    // TODO
  }
}
