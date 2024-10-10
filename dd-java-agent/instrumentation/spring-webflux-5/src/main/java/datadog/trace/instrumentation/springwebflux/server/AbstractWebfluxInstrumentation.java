package datadog.trace.instrumentation.springwebflux.server;

import datadog.trace.agent.tooling.InstrumenterModule;

public abstract class AbstractWebfluxInstrumentation extends InstrumenterModule.Tracing {

  public AbstractWebfluxInstrumentation(final String... additionalNames) {
    super("spring-webflux", additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebfluxHttpServerDecorator",
      packageName + ".AdviceUtils",
      packageName + ".AdviceUtils$MonoSpanFinisher",
      packageName + ".RouteOnSuccessOrError"
    };
  }
}
