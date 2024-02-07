package datadog.trace.instrumentation.aws.v2;

import datadog.trace.agent.tooling.InstrumenterGroup;

public abstract class AbstractAwsClientInstrumentation extends InstrumenterGroup.Tracing {
  private static final String INSTRUMENTATION_NAME = "aws-sdk";

  public AbstractAwsClientInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AwsSdkClientDecorator", packageName + ".TracingExecutionInterceptor"
    };
  }
}
