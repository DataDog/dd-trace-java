package datadog.trace.instrumentation.aws.v0;

import com.amazonaws.handlers.HandlerContextKey;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class OnErrorDecorator extends BaseDecorator {

  public static final HandlerContextKey<AgentSpan> SPAN_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogSpan"); // same as TracingRequestHandler.SPAN_CONTEXT_KEY

  public static final OnErrorDecorator DECORATE = new OnErrorDecorator();

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"aws-sdk"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }
}
