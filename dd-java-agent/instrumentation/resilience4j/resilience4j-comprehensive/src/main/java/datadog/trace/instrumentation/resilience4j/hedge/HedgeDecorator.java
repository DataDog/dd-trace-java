package datadog.trace.instrumentation.resilience4j.hedge;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import io.github.resilience4j.hedge.Hedge;

public final class HedgeDecorator extends Resilience4jSpanDecorator<Hedge> {
  public static final HedgeDecorator DECORATE = new HedgeDecorator();
  public static final String TAG_PREFIX = "resilience4j.hedge.";

  private HedgeDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, Hedge data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    span.setTag(TAG_PREFIX + "delay_duration_ms", data.getHedgeConfig().getDelay().toMillis());
  }
}
