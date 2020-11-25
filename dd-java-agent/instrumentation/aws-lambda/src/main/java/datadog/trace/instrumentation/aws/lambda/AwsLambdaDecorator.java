package datadog.trace.instrumentation.aws.lambda;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class AwsLambdaDecorator extends BaseDecorator {
  public static final AwsLambdaDecorator DECORATE = new AwsLambdaDecorator();

  private static final UTF8BytesString FUNCTION_NAME =
      UTF8BytesString.create(System.getenv("AWS_LAMBDA_FUNCTION_NAME"));

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    super.afterStart(span);

    span.setResourceName(FUNCTION_NAME);

    if (!Config.get().isServiceNameSetByUser()) {
      span.setServiceName("aws.lambda");
    }

    return span;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"aws-lambda"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return "aws-lambda";
  }
}
