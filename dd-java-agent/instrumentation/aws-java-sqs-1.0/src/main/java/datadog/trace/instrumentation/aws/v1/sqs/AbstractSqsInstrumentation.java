package datadog.trace.instrumentation.aws.v1.sqs;

import datadog.trace.agent.tooling.Instrumenter;

public abstract class AbstractSqsInstrumentation extends Instrumenter.Tracing {
  public AbstractSqsInstrumentation() {
    super("sqs", "aws-sdk");
  }
}
