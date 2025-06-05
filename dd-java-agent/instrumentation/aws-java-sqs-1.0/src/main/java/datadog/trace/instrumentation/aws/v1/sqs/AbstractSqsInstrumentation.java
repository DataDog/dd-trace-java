package datadog.trace.instrumentation.aws.v1.sqs;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.util.Strings;

public abstract class AbstractSqsInstrumentation extends InstrumenterModule.Tracing {
  public AbstractSqsInstrumentation() {
    this(NO_ADDITIONAL_NAMES);
  }

  public AbstractSqsInstrumentation(String... additionalNames) {
    super("sqs", Strings.concat(additionalNames, "aws-sdk"));
  }
}
