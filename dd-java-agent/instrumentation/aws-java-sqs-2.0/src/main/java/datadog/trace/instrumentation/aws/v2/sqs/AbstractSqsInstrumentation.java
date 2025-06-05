package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.agent.tooling.InstrumenterModule;

public abstract class AbstractSqsInstrumentation extends InstrumenterModule.Tracing {
  public AbstractSqsInstrumentation() {
    this(NO_ADDITIONAL_NAMES);
  }

  public AbstractSqsInstrumentation(String... additionalNames) {
    super("sqs", concat(additionalNames, "aws-sdk"));
  }
}
