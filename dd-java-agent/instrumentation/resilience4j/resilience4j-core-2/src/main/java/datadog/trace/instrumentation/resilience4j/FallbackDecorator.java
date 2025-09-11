package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class FallbackDecorator extends Resilience4jSpanDecorator<Void> {
  public static final FallbackDecorator DECORATE = new FallbackDecorator();

  private FallbackDecorator() {
    super();
  }

  @Override
  public void decorate(final AgentSpan span, final Void data) {
    // noop
    //    span.setSpanName("resilience4j.fallback");
  }
}
