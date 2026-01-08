package datadog.trace.instrumentation.resilience4j.cache;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import javax.cache.Cache;

public final class CacheDecorator extends Resilience4jSpanDecorator<Cache<?, ?>> {
  public static final CacheDecorator DECORATE = new CacheDecorator();
  public static final String TAG_PREFIX = "resilience4j.cache.";

  private CacheDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, Cache<?, ?> data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
  }
}
