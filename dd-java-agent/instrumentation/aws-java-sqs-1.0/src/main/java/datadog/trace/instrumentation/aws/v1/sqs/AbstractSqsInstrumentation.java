package datadog.trace.instrumentation.aws.v1.sqs;

import datadog.trace.agent.tooling.InstrumenterModule;

public abstract class AbstractSqsInstrumentation extends InstrumenterModule.Tracing {
  public AbstractSqsInstrumentation() {
    super("sqs", "aws-sdk");
  }
}
