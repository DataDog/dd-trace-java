package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public abstract class AbstractResilience4jDecorator<T> extends BaseDecorator {
  public static final CharSequence RESILIENCE4J = UTF8BytesString.create("resilience4j");
  public static final CharSequence SPAN_NAME = UTF8BytesString.create("resilience4j");
  public static final String INSTRUMENTATION_NAME = "resilience4j";

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

  public abstract void decorate(AgentSpan span, T data);

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    super.afterStart(span);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
    return span;
  }
}
