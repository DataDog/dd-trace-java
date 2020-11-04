package datadog.trace.instrumentation.jdbc;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.ExceptionLogger;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

public abstract class JDBCUtils {
  private static Field c3poField = null;

  // Cache if the class's isWrapperFor() or unwrap() methods are abstract
  // Using classnames to avoid the need for a WeakMap
  public static final DDCache<String, Boolean> ABSTRACT_UNWRAP = DDCaches.newFixedSizeCache(64);

  public static PreparedStatement unwrappedStatement(PreparedStatement statement) {
    Boolean abstractUnwrap = ABSTRACT_UNWRAP.getIfPresent(statement.getClass().getName());

    if (abstractUnwrap == null) {
      return tryUnwrap(statement);
    } else {
      return statement;
    }
  }

  private static PreparedStatement tryUnwrap(PreparedStatement statement) {
    try {
      // technically this could be recursive. In practice, one level is enough
      if (statement.isWrapperFor(PreparedStatement.class)) {
        return statement.unwrap(PreparedStatement.class);
      }
    } catch (AbstractMethodError e) {
      ABSTRACT_UNWRAP.put(statement.getClass().getName(), true);

      // perhaps wrapping isn't supported?
      // ex: org.h2.jdbc.JdbcConnection v1.3.175
      // or: jdts.jdbc which always throws `AbstractMethodError` (at least up to version 1.3)
      // Stick with original statement.
    } catch (Exception ignored) {
    }

    return statement;
  }

  /**
   * @param statement
   * @return the unwrapped connection or null if exception was thrown.
   */
  public static Connection connectionFromStatement(final Statement statement) {
    Connection connection;
    try {
      connection = statement.getConnection();

      if (c3poField != null) {
        if (connection.getClass().getName().equals("com.mchange.v2.c3p0.impl.NewProxyConnection")) {
          return (Connection) c3poField.get(connection);
        }
      }

      Boolean abstractUnwrap = ABSTRACT_UNWRAP.getIfPresent(connection.getClass().getName());

      if (abstractUnwrap == null) {
        return tryUnwrap(connection);
      } else {
        return connection;
      }
    } catch (final Throwable e) {
      // Had some problem getting the connection.
      ExceptionLogger.LOGGER.debug("Could not get connection for StatementAdvice", e);
      return null;
    }
  }

  private static Connection tryUnwrap(Connection connection) throws ReflectiveOperationException {
    try {
      // technically this could be recursive. In practice, one level is enough
      if (connection.isWrapperFor(Connection.class)) {
        return connection.unwrap(Connection.class);
      } else {
        return connection;
      }
    } catch (AbstractMethodError e) {
      ABSTRACT_UNWRAP.put(connection.getClass().getName(), true);
      // perhaps wrapping isn't supported?
      // ex: org.h2.jdbc.JdbcConnection v1.3.175
      // or: jdts.jdbc which always throws `AbstractMethodError` (at least up to version 1.3)
      // Stick with original connection.
    } catch (Exception ignored) {
    }

    // Attempt to work around c3po delegating to an connection that doesn't support
    // unwrapping.
    final Class<? extends Connection> connectionClass = connection.getClass();
    if (connectionClass.getName().equals("com.mchange.v2.c3p0.impl.NewProxyConnection")) {
      final Field inner = connectionClass.getDeclaredField("inner");
      inner.setAccessible(true);
      c3poField = inner;
      return (Connection) c3poField.get(connection);
    }

    return connection;
  }
}
