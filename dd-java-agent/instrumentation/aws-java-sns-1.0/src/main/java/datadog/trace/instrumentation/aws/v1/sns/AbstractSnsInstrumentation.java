package datadog.trace.instrumentation.aws.v1.sns;

import datadog.trace.agent.tooling.InstrumenterModule;

public abstract class AbstractSnsInstrumentation extends InstrumenterModule.Tracing {
  public AbstractSnsInstrumentation() {
    super("sns", "aws-sdk");
  }
}
