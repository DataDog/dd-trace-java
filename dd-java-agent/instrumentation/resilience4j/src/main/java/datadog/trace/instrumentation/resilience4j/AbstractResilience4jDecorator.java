package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public abstract class AbstractResilience4jDecorator<T> extends BaseDecorator {
  @Override
  protected String[] instrumentationNames() {
    return new String[] {"resilience4j"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return null;
  }

  protected abstract void decorate(AgentScope scope, T data);
}
