package datadog.trace.instrumentation.jdbc.mysql;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.jdbc.JdbcConnection;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public final class ConnectorJIntrospection {

  private ConnectorJIntrospection() {}

  public static PropertySet properties(final Connection connection) throws SQLException {
    return connection.unwrap(JdbcConnection.class).getPropertySet();
  }

  public static boolean cachePrepStmts(final Connection connection) throws SQLException {
    return properties(connection).getBooleanProperty(PropertyKey.cachePrepStmts).getValue();
  }

  public static boolean useServerPrepStmts(final Connection connection) throws SQLException {
    return properties(connection).getBooleanProperty(PropertyKey.useServerPrepStmts).getValue();
  }

  public static int prepStmtCacheSize(final Connection connection) throws SQLException {
    return properties(connection).getIntegerProperty(PropertyKey.prepStmtCacheSize).getValue();
  }

  public static int prepStmtCacheSqlLimit(final Connection connection) throws SQLException {
    return properties(connection).getIntegerProperty(PropertyKey.prepStmtCacheSqlLimit).getValue();
  }

  public static int cachedStatementCountOrMinusOne(final Connection connection)
      throws SQLException {
    final Object impl = connection.unwrap(JdbcConnection.class);
    for (Class<?> c = impl.getClass(); c != null; c = c.getSuperclass()) {
      try {
        final Field f = c.getDeclaredField("queryInfoCache");
        f.setAccessible(true);
        final Object cache = f.get(impl);
        if (cache == null) {
          return -1;
        }
        final Field inner = cache.getClass().getDeclaredField("cache");
        inner.setAccessible(true);
        return ((Map<?, ?>) inner.get(cache)).size();
      } catch (NoSuchFieldException e) {
        continue;
      } catch (ReflectiveOperationException | ClassCastException e) {
        return -1;
      }
    }
    return -1;
  }
}
