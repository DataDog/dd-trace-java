package datadog.trace.instrumentation.springwebflux.server;

import datadog.trace.agent.tooling.InstrumenterGroup;

public abstract class AbstractWebfluxInstrumentation extends InstrumenterGroup.Tracing {

  public AbstractWebfluxInstrumentation(final String... additionalNames) {
    super("spring-webflux", additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebfluxHttpServerDecorator",
      packageName + ".AdviceUtils",
      packageName + ".AdviceUtils$SpanSubscriber",
      packageName + ".AdviceUtils$SpanFinishingSubscriber",
      packageName + ".RouteOnSuccessOrError"
    };
  }
}
