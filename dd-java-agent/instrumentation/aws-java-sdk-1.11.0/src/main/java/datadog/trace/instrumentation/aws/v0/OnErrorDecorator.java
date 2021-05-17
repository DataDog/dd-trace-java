package datadog.trace.instrumentation.aws.v0;

import com.amazonaws.handlers.HandlerContextKey;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class OnErrorDecorator extends BaseDecorator {
  // aws1.x sdk doesn't have any truly async clients so we can store scope in request context safely
  public static final HandlerContextKey<AgentScope> SCOPE_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogScope"); // same as TracingRequestHandler.SCOPE_CONTEXT_KEY

  public static final OnErrorDecorator DECORATE = new OnErrorDecorator();
  private static final CharSequence JAVA_AWS_SDK = UTF8BytesString.create("java-aws-sdk");

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
    return JAVA_AWS_SDK;
  }
}
