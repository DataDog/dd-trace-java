package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.ExceptionLogger;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

public abstract class JDBCUtils {
  private static Field c3poField = null;

  // Cache if the class's isWrapperFor() or unwrap() methods are abstract
  // Using classnames to avoid the need for a WeakMap
  private static final ClassValue<Boolean> CAN_UNWRAP =
      new ClassValue<Boolean>() {

        private boolean hasMethod(Class<?> type, String name, Class<?> param) {
          try {
            type.getDeclaredMethod(name, Class.class).invoke(null, param);
          } catch (NoSuchMethodException | AbstractMethodError e) {
            // perhaps wrapping isn't supported?
            // ex: org.h2.jdbc.JdbcConnection v1.3.175
            // or: jdts.jdbc which always throws `AbstractMethodError` (at least up to version 1.3)
            // Return false to indicate that we should stick with original statement.
            return false;
          } catch (Exception ignore) {
          }
          return true;
        }

        @Override
        protected Boolean computeValue(Class<?> type) {
          if (Connection.class.isAssignableFrom(type)) {
            return hasMethod(type, "unwrap", Connection.class)
                && hasMethod(type, "isWrapperFor", Connection.class);
          } else if (PreparedStatement.class.isAssignableFrom(type)) {
            return hasMethod(type, "unwrap", PreparedStatement.class)
                && hasMethod(type, "isWrapperFor", PreparedStatement.class);
          }
          return false;
        }
      };

  public static PreparedStatement unwrappedStatement(PreparedStatement statement) {
    if (CAN_UNWRAP.get(statement.getClass())) {
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
    } catch (Exception ignored) {
      // note that we will not even call this method unless it has been shown not to
      // throw AbstractMethodError
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

      if (CAN_UNWRAP.get(connection.getClass())) {
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
    } catch (Exception ignored) {
      // note that we will not even call this method unless it has been shown not to
      // throw AbstractMethodError
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
