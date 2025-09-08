package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public abstract class AbstractResilience4jDecorator<T> extends BaseDecorator {
  private static final CharSequence RESILIENCE4J = UTF8BytesString.create("resilience4j");

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
    return RESILIENCE4J;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    super.afterStart(span);
    span.setSpanName(RESILIENCE4J);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
    return span;
  }

  public abstract void decorate(AgentSpan span, T data);
}
