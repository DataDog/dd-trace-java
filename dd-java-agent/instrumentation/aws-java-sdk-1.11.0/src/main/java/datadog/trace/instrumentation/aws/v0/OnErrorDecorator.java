package datadog.trace.instrumentation.aws.v0;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class OnErrorDecorator extends BaseDecorator {
  public static final OnErrorDecorator DECORATE = new OnErrorDecorator();
  private static final CharSequence JAVA_AWS_SDK = UTF8BytesString.createConstant("java-aws-sdk");

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
