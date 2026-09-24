package datadog.trace.instrumentation.r2dbc;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

/**
 * Wraps a {@link ConnectionFactory} with r2dbc-proxy to install a tracing listener, and registers
 * each real driver {@link Connection} with its {@link ConnectionFactoryOptions} in {@link
 * R2dbcConnectionMetadataStore} so that DBM SQL comment injection can access connection metadata
 * (host, database, driver type).
 */
public final class R2dbcTracingSupport {

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
   * A lightweight listener that tracks connection creation events to associate the real driver
   * {@link Connection} with the {@link ConnectionFactoryOptions} used to create it. This allows
   * {@link R2dbcConnectionInstrumentation} to look up connection metadata when injecting SQL
   * comments on the real driver's {@code createStatement}.
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
        // Connection was successfully created — register the metadata keyed by the REAL driver
        // connection (getOriginalConnection), which is the instance the driver's createStatement
        // advice sees via @Advice.This.
        Connection realConnection = connInfo.getOriginalConnection();
        R2dbcConnectionMetadataStore.register(realConnection, options);
      }
    }
  }
}
