package datadog.trace.instrumentation.r2dbc;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps a {@link ConnectionFactory} with r2dbc-proxy to install a tracing listener. Also maintains
 * a mapping from {@link ConnectionInfo} to {@link ConnectionFactoryOptions} so that DBM SQL comment
 * injection can access connection metadata (host, database, driver type).
 */
public final class R2dbcTracingSupport {

  /**
   * Maps R2DBC proxy {@link ConnectionInfo} instances to the {@link ConnectionFactoryOptions} used
   * to create the connection factory. This allows the DBM SQL comment injector to resolve
   * connection metadata (hostname, database name, db type) when intercepting {@code
   * createStatement} calls.
   *
   * <p>Entries are added when the proxy listener's {@code afterMethod} fires for {@code create()}
   * (connection creation), and removed when the connection is closed.
   */
  static final Map<ConnectionInfo, ConnectionFactoryOptions> CONNECTION_OPTIONS =
      new ConcurrentHashMap<>();

  private R2dbcTracingSupport() {}

  public static ConnectionFactory wrapConnectionFactory(
      ConnectionFactory factory, ConnectionFactoryOptions options) {
    TraceProxyExecutionListener queryListener = new TraceProxyExecutionListener(options);
    ConnectionMetadataListener metadataListener = new ConnectionMetadataListener(options);

    return ProxyConnectionFactory.builder(factory)
        .listener(queryListener)
        .listener(metadataListener)
        .build();
  }

  /**
   * A lightweight listener that tracks connection creation and close events to maintain the {@link
   * #CONNECTION_OPTIONS} map. This allows {@link R2dbcConnectionCallbackInstrumentation} to look up
   * connection metadata when injecting SQL comments.
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
        // Connection was successfully created — register the metadata
        CONNECTION_OPTIONS.put(connInfo, options);
      } else if ("close".equals(methodName)) {
        // Connection closed — clean up
        CONNECTION_OPTIONS.remove(connInfo);
      }
    }
  }
}
