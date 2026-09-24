package datadog.trace.instrumentation.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Associates the real driver {@link Connection} with the {@link ConnectionFactoryOptions} used to
 * create it, so DBM SQL comment injection can resolve connection metadata (service, db type, host,
 * db name) when advising the driver's {@code Connection#createStatement}.
 *
 * <p>This is a dependency-free holder deliberately separated from {@link R2dbcTracingSupport}
 * (which pulls in the whole r2dbc-proxy wrapping graph). Keeping the store standalone lets the DBM
 * module ({@link R2dbcConnectionInstrumentation}) list a minimal helper closure — it needs the map
 * and the decorator, not the proxy machinery.
 *
 * <p>Backed by a {@link WeakHashMap} keyed by the {@link Connection}: entries are evicted once the
 * connection is unreachable (pool eviction, timeout, unclean disconnect), so nothing leaks even
 * when {@code close()} is never observed. Wrapped in {@link Collections#synchronizedMap} because
 * the writer (proxy metadata listener) and reader (createStatement advice) can run on different
 * threads.
 */
public final class R2dbcConnectionMetadataStore {

  static final Map<Connection, ConnectionFactoryOptions> CONNECTION_OPTIONS =
      Collections.synchronizedMap(new WeakHashMap<>());

  private R2dbcConnectionMetadataStore() {}

  public static void register(Connection connection, ConnectionFactoryOptions options) {
    if (connection != null && options != null) {
      CONNECTION_OPTIONS.put(connection, options);
    }
  }

  public static ConnectionFactoryOptions get(Connection connection) {
    return CONNECTION_OPTIONS.get(connection);
  }
}
