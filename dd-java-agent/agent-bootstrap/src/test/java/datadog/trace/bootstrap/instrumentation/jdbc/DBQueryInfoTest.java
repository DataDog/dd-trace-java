package datadog.trace.bootstrap.instrumentation.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DBQueryInfoTest {

  @Test
  void nullStatementHasNoQueryInfo() {
    assertNull(DBQueryInfo.ofStatement(null));
  }

  @Test
  void nullPreparedStatementHasNoQueryInfo() {
    assertNull(DBQueryInfo.ofPreparedStatement(null));
  }

  @Test
  void constructingFromNullSqlDoesNotThrow() {
    DBQueryInfo info = new DBQueryInfo(null);
    assertNull(info.getSql());
    assertNull(info.getOperation());
  }

  @Test
  void normalizesStatement() {
    DBQueryInfo info = DBQueryInfo.ofStatement("SELECT * FROM t WHERE id = 42");
    assertEquals("SELECT * FROM t WHERE id = ?", info.getSql().toString());
    assertEquals("SELECT", info.getOperation().toString());
  }
}
