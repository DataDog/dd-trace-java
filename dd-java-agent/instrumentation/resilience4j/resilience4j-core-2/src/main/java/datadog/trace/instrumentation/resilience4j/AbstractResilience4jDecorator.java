package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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

  public abstract void decorate(AgentSpan span, T data);
}
