package datadog.trace.instrumentation.lettuce5.rx;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class RedisSubscriptionState {
  public boolean cancelled = false;
  public int count = 0;
  public AgentSpan parentSpan;
}
