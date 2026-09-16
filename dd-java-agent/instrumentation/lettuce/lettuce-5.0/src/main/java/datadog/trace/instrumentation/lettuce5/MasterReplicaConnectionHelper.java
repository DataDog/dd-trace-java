package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import java.util.function.BiConsumer;

public final class MasterReplicaConnectionHelper {

  private MasterReplicaConnectionHelper() {}

  public static boolean isRedisClientSpan(final AgentSpan span) {
    return span != null && LettuceClientDecorator.REDIS_CLIENT.equals(span.getTag(Tags.COMPONENT));
  }

  public static void onConnection(
      final AgentSpan span,
      final StatefulConnection connection,
      final ContextStore<StatefulConnection, RedisURI> contextStore) {
    if (connection == null) {
      return;
    }

    final RedisURI redisURI = contextStore.get(connection);
    if (redisURI != null) {
      DECORATE.onConnection(span, redisURI);
    }
  }

  public static BiConsumer<StatefulConnection, Throwable> onConnectionComplete(
      final AgentSpan span, final ContextStore<StatefulConnection, RedisURI> contextStore) {
    return (connection, _throwable) -> onConnection(span, connection, contextStore);
  }
}
