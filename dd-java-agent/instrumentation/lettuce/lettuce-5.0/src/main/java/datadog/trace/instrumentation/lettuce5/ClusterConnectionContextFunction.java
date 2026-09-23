package datadog.trace.instrumentation.lettuce5;

import datadog.trace.bootstrap.ContextStore;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Function;

public class ClusterConnectionContextFunction<T extends StatefulConnection>
    implements Function<T, T> {

  private final ConnectionFuture<? extends StatefulConnection> connectionFuture;
  private final ContextStore<StatefulConnection, RedisURI> contextStore;

  public ClusterConnectionContextFunction(
      final ConnectionFuture<? extends StatefulConnection> connectionFuture,
      final ContextStore<StatefulConnection, RedisURI> contextStore) {
    this.connectionFuture = connectionFuture;
    this.contextStore = contextStore;
  }

  @Override
  public T apply(final T connection) {
    if (connection == null) {
      return null;
    }

    try {
      final RedisURI connectionURI = redisUriFromConnectionFuture();
      if (connectionURI != null) {
        contextStore.put(connection, connectionURI);
      }
    } catch (final Throwable ignored) {
    }
    return connection;
  }

  private RedisURI redisUriFromConnectionFuture() {
    if (connectionFuture == null) {
      return null;
    }

    final SocketAddress socketAddress = connectionFuture.getRemoteAddress();
    if (socketAddress instanceof InetSocketAddress) {
      final InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
      return RedisURI.create(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
    }

    return null;
  }
}
