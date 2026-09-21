package datadog.trace.bootstrap.instrumentation.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JDBCConnectionContextTest {

  @Test
  void keepsMutableStateSeparateFromDatabaseInfo() {
    DBInfo dbInfo = new DBInfo.Builder().type("oracle").build();
    JDBCConnectionContext first = new JDBCConnectionContext(dbInfo);
    JDBCConnectionContext second = new JDBCConnectionContext(dbInfo);

    assertSame(dbInfo, first.getDbInfo());
    assertSame(dbInfo, second.getDbInfo());
    assertNull(first.getPoolName());
    assertNull(second.getPoolName());

    first.setPoolName("first-pool");

    assertEquals("first-pool", first.getPoolName());
    assertNull(second.getPoolName());
  }

  @Test
  void tracksTheLastSuccessfullySetOracleServiceHash() {
    JDBCConnectionContext context = oracleContext();

    assertTrue(context.shouldSetOracleServiceHash("123"));
    assertFalse(context.isOracleServiceHashSet("123"));

    context.markOracleServiceHashSet("123");

    assertFalse(context.shouldSetOracleServiceHash("123"));
    assertTrue(context.isOracleServiceHashSet("123"));
    assertFalse(context.isOracleServiceHashSet("456"));
    assertTrue(context.shouldSetOracleServiceHash("456"));
  }

  @Test
  void skipsActionAfterDriverIsMarkedUnsupported() {
    JDBCConnectionContext context = oracleContext();

    context.markOracleServiceActionUnsupported();

    assertFalse(context.shouldSetOracleServiceHash("123"));
  }

  private static JDBCConnectionContext oracleContext() {
    return new JDBCConnectionContext(new DBInfo.Builder().type("oracle").build());
  }
}
