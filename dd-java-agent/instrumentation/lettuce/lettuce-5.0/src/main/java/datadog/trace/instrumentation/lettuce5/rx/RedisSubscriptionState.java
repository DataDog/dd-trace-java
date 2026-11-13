package datadog.trace.instrumentation.lettuce5.rx;

import io.lettuce.core.api.StatefulConnection;

public final class RedisSubscriptionState {
  public boolean cancelled = false;
  public int count = 0;
  public StatefulConnection<?, ?> connection;
}
