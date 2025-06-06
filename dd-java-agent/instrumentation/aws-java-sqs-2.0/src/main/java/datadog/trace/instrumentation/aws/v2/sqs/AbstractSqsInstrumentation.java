package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.util.Strings;

public abstract class AbstractSqsInstrumentation extends InstrumenterModule.Tracing {
  public AbstractSqsInstrumentation() {
    super("sqs", "aws-sdk");
  }

  public AbstractSqsInstrumentation(String... additionalNames) {
    super("sqs", Strings.concat(additionalNames, "aws-sdk"));
  }
}
