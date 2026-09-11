package datadog.trace.instrumentation.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.lang.reflect.Method;

/**
 * Extracts connection metadata from an R2DBC ConnectionFactory by looking for a {@code
 * getOptions()} method that returns ConnectionFactoryOptions. This covers most R2DBC driver
 * implementations.
 */
public final class R2dbcConnectionInfoExtractor {

  public static R2dbcConnectionInfo extract(ConnectionFactory factory) {
    try {
      Method getOptions = null;
      try {
        getOptions = factory.getClass().getMethod("getOptions");
      } catch (NoSuchMethodException e) {
        // Not available
      }

      if (getOptions != null) {
        Object options = getOptions.invoke(factory);
        if (options instanceof ConnectionFactoryOptions) {
          return fromOptions((ConnectionFactoryOptions) options);
        }
      }
    } catch (Exception e) {
      // Silently ignore - metadata extraction is best-effort
    }
    return null;
  }

  public static R2dbcConnectionInfo fromOptions(ConnectionFactoryOptions cfo) {
    String host = safeGet(cfo, ConnectionFactoryOptions.HOST);
    Integer port = safeGetInt(cfo, ConnectionFactoryOptions.PORT);
    String user = safeGet(cfo, ConnectionFactoryOptions.USER);
    String database = safeGet(cfo, ConnectionFactoryOptions.DATABASE);
    String driver = safeGet(cfo, ConnectionFactoryOptions.DRIVER);

    if (host != null || user != null || database != null || driver != null) {
      return new R2dbcConnectionInfo(host, port, user, database, driver);
    }
    return null;
  }

  private static String safeGet(ConnectionFactoryOptions options, Option<?> option) {
    try {
      if (options.hasOption(option)) {
        Object value = options.getValue(option);
        return value != null ? value.toString() : null;
      }
    } catch (Exception e) {
      // ignore
    }
    return null;
  }

  private static Integer safeGetInt(ConnectionFactoryOptions options, Option<?> option) {
    try {
      if (options.hasOption(option)) {
        Object value = options.getValue(option);
        if (value instanceof Integer) {
          return (Integer) value;
        } else if (value != null) {
          return Integer.parseInt(value.toString());
        }
      }
    } catch (Exception e) {
      // ignore
    }
    return null;
  }

  private R2dbcConnectionInfoExtractor() {}
}
