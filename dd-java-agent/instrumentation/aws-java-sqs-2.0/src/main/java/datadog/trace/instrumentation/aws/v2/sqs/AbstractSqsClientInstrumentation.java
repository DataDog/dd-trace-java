package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.agent.tooling.Instrumenter;

public abstract class AbstractSqsClientInstrumentation extends Instrumenter.Tracing {
  private static final String INSTRUMENTATION_NAME = "aws-sdk";

  public AbstractSqsClientInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SqsInterceptor", "datadog.trace.instrumentation.aws.v2.AwsExecutionAttribute",
        packageName + ".MessageAttributeInjector",
    };
  }
}
