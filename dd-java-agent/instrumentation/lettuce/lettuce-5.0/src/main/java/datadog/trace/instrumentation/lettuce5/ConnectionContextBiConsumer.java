package datadog.trace.instrumentation.lettuce5;

import datadog.trace.bootstrap.ContextStore;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import java.util.function.BiConsumer;

public class ConnectionContextBiConsumer implements BiConsumer<StatefulConnection, Throwable> {

  private final RedisURI redisURI;
  private final ContextStore<StatefulConnection, RedisURI> contextStore;

  public ConnectionContextBiConsumer(
      RedisURI redisURI, ContextStore<StatefulConnection, RedisURI> contextStore) {
    this.redisURI = redisURI;
    this.contextStore = contextStore;
  }

  @Override
  public void accept(StatefulConnection statefulConnection, Throwable throwable) {
    if (statefulConnection != null) {
      contextStore.put(statefulConnection, redisURI);
    }
  }
}
