package datadog.trace.instrumentation.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** How {@code parseDBInfoFromConnection} copes with connections whose getClientInfo fails. */
class ParseDBInfoClientInfoTest {
  private static final String URL = "jdbc:postgresql://db.example.com:5432/orders";

  interface ClientInfoAnswer {
    Properties get() throws Throwable;
  }

  private static Connection connection(AtomicInteger clientInfoCalls, ClientInfoAnswer answer) {
    DatabaseMetaData metaData =
        (DatabaseMetaData)
            Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) -> "getURL".equals(method.getName()) ? URL : null);
    InvocationHandler handler =
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getMetaData":
              return metaData;
            case "getClientInfo":
              clientInfoCalls.incrementAndGet();
              try {
                return answer.get();
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            default:
              return null;
          }
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  @Test
  void urlIsStillParsedWhenGetClientInfoThrowsSqlException() {
    AtomicInteger calls = new AtomicInteger();
    Connection connection =
        connection(
            calls,
            () -> {
              throw new SQLException("not allowed");
            });

    DBInfo info = JDBCDecorator.parseDBInfoFromConnection(connection);

    assertEquals("postgresql", info.getType());
    assertEquals("orders", info.getDb());
  }

  @Test
  void urlIsStillParsedWhenGetClientInfoIsUnsupported() {
    AtomicInteger calls = new AtomicInteger();
    Connection connection =
        connection(
            calls,
            () -> {
              throw new UnsupportedOperationException();
            });

    DBInfo info = JDBCDecorator.parseDBInfoFromConnection(connection);

    assertEquals("postgresql", info.getType());
    assertEquals("orders", info.getDb());
  }

  @Test
  void urlIsStillParsedWhenGetClientInfoIsMissing() {
    AtomicInteger calls = new AtomicInteger();
    Connection connection =
        connection(
            calls,
            () -> {
              throw new AbstractMethodError("driver predates JDBC 4.0");
            });

    DBInfo info = JDBCDecorator.parseDBInfoFromConnection(connection);

    assertEquals("postgresql", info.getType());
    assertEquals("orders", info.getDb());
  }

  @Test
  void anyOtherFailureStillYieldsUrlBasedDbInfo() {
    AtomicInteger calls = new AtomicInteger();
    for (Throwable failure :
        new Throwable[] {
          new IllegalStateException("unexpected"), new Throwable("not even an Exception")
        }) {
      Connection connection =
          connection(
              calls,
              () -> {
                throw failure;
              });

      // getClientInfo can fail in any way; the URL alone is still enough for the DB info
      DBInfo info = JDBCDecorator.parseDBInfoFromConnection(connection);

      assertEquals("postgresql", info.getType());
      assertEquals("orders", info.getDb());
    }
  }

  @Test
  void returnsTheClientInfoWhenAvailable() {
    AtomicInteger calls = new AtomicInteger();
    Properties clientInfo = new Properties();
    Connection connection = connection(calls, () -> clientInfo);

    DBInfo info = JDBCDecorator.parseDBInfoFromConnection(connection);

    assertEquals("postgresql", info.getType());
    assertEquals(1, calls.get());
  }
}
