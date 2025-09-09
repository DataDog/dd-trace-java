package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class NoopDecorator extends AbstractResilience4jDecorator<Void> {
  public static final NoopDecorator DECORATE = new NoopDecorator();

  private NoopDecorator() {
    super();
  }

  @Override
  public void decorate(final AgentSpan span, final Void data) {
    // noop
    //    span.setSpanName("resilience4j.fallback");
  }
}
