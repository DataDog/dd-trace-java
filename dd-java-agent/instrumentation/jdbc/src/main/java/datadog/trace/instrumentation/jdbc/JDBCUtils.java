package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.ExceptionLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

public abstract class JDBCUtils {
  private static Field c3poField = null;

  // Cache if the class's isWrapperFor() or unwrap() methods are usable
  private static final ClassValue<Boolean> CAN_UNWRAP =
      new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
          // DB drivers compiled before JDK 1.6 do not implement java.sql.Wrapper methods and throw
          // AbstractMethodError when called
          // h2 before v1.3.168 and jdts.jdbc up to version 1.3 are in this category
          // Additionally h2, instead of returning "false" in versions 1.3.169+, throws an exception
          // so those classes are hardcoded below
          try {
            switch (type.getName()) {
              case "org.h2.jdbc.JdbcConnection":
              case "org.h2.jdbc.JdbcPreparedStatement":
              case "org.h2.jdbc.JdbcCallableStatement":
              case "org.h2.jdbcx.JdbcXAConnection.PooledJdbcConnection":
                return false;
              default:
                return !Modifier.isAbstract(
                        type.getMethod("isWrapperFor", Class.class).getModifiers())
                    && !Modifier.isAbstract(type.getMethod("unwrap", Class.class).getModifiers());
            }
          } catch (NoSuchMethodException ignored) {
          }
          return false;
        }
      };

  public static PreparedStatement unwrappedStatement(PreparedStatement statement) {
    PreparedStatement origin = statement;
    try {
      while (CAN_UNWRAP.get(statement.getClass())
          && statement.isWrapperFor(PreparedStatement.class)) {
        PreparedStatement unwrapped = statement.unwrap(PreparedStatement.class);
        if (unwrapped == origin) { // cycle detected
          return statement;
        }
        statement = unwrapped;
      }
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
