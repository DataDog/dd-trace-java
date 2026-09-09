package datadog.trace.instrumentation.lettuce5;

import datadog.trace.bootstrap.ContextStore;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import java.util.function.Function;

public class ConnectionContextFunction implements Function<StatefulConnection, StatefulConnection> {

  private final RedisURI redisURI;
  private final ContextStore<StatefulConnection, RedisURI> contextStore;

  public ConnectionContextFunction(
      RedisURI redisURI, ContextStore<StatefulConnection, RedisURI> contextStore) {
    this.redisURI = redisURI;
    this.contextStore = contextStore;
  }

  @Override
  public StatefulConnection apply(StatefulConnection statefulConnection) {
    if (statefulConnection != null) {
      contextStore.put(statefulConnection, redisURI);
    }
    return statefulConnection;
  }
}
