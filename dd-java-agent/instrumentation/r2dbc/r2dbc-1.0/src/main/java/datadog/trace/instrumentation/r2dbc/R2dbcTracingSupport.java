package datadog.trace.instrumentation.r2dbc;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Wraps a {@link ConnectionFactory} with r2dbc-proxy to install a tracing listener. Also maintains
 * a mapping from {@link ConnectionInfo} to the {@link ConnectionFactoryOptions} used to create it,
 * so that DBM SQL comment injection can access connection metadata (host, database, driver type).
 */
public final class R2dbcTracingSupport {

  /**
   * Maps R2DBC proxy {@link ConnectionInfo} instances to the {@link ConnectionFactoryOptions} used
   * to create the connection factory. Read by {@link R2dbcConnectionCallbackInstrumentation} and
   * {@link R2dbcBatchCallbackInstrumentation} to resolve connection metadata (hostname, database
   * name, db type) when injecting DBM SQL comments.
   *
   * <p>This is a {@link WeakHashMap} keyed by {@link ConnectionInfo}: entries are evicted
   * automatically once a {@link ConnectionInfo} becomes unreachable (i.e. its connection is no
   * longer referenced), so a connection that is dropped abruptly — pool eviction, timeout, an
   * unclean disconnect that never fires {@code close()} — does not leak its entry. Wrapped with
   * {@link Collections#synchronizedMap} because r2dbc-proxy listener callbacks can fire from
   * multiple threads.
   */
  static final Map<ConnectionInfo, ConnectionFactoryOptions> CONNECTION_OPTIONS =
      Collections.synchronizedMap(new WeakHashMap<>());

  private R2dbcTracingSupport() {}

  public static ConnectionFactory wrapConnectionFactory(
      ConnectionFactory factory, ConnectionFactoryOptions options) {
    // r2dbc-proxy is bundled as a real dependency (see build.gradle), so its own
    // ConnectionFactoryProvider is discoverable by ConnectionFactories.find() like any
    // other driver. If an application itself requests DRIVER="proxy" (r2dbc-proxy's own
    // URL scheme), find() already returns an r2dbc-proxy-wrapped factory — wrapping it
    // again here would double the query listener callbacks (duplicate spans).
    if ("proxy".equals(options.getValue(ConnectionFactoryOptions.DRIVER))) {
      return factory;
    }

    TraceProxyExecutionListener queryListener = new TraceProxyExecutionListener(options);
    ConnectionMetadataListener metadataListener = new ConnectionMetadataListener(options);

    return ProxyConnectionFactory.builder(factory)
        .listener(queryListener)
        .listener(metadataListener)
        .build();
  }

  /**
   * A lightweight listener that tracks connection creation events to associate {@link
   * ConnectionInfo} with the {@link ConnectionFactoryOptions} used to create it. This allows {@link
   * R2dbcConnectionCallbackInstrumentation} and {@link R2dbcBatchCallbackInstrumentation} to look
   * up connection metadata when injecting SQL comments.
   */
  static final class ConnectionMetadataListener implements ProxyExecutionListener {
    private final ConnectionFactoryOptions options;

    ConnectionMetadataListener(ConnectionFactoryOptions options) {
      this.options = options;
    }

    @Override
    public void afterMethod(io.r2dbc.proxy.core.MethodExecutionInfo execInfo) {
      String methodName = execInfo.getMethod().getName();
      ConnectionInfo connInfo = execInfo.getConnectionInfo();
      if (connInfo == null) {
        return;
      }
      if ("create".equals(methodName) && execInfo.getThrown() == null) {
        // Connection was successfully created — register the metadata. No explicit removal on
        // close(): the WeakHashMap evicts the entry once this ConnectionInfo is unreachable.
        CONNECTION_OPTIONS.put(connInfo, options);
      }
    }
  }
}
