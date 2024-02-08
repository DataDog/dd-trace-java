package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.agent.tooling.InstrumenterGroup;

public abstract class AbstractSqsInstrumentation extends InstrumenterGroup.Tracing {
  public AbstractSqsInstrumentation() {
    super("sqs", "aws-sdk");
  }
}
