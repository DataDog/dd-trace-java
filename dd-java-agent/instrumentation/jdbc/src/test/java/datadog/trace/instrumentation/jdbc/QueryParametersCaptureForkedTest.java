package datadog.trace.instrumentation.jdbc;

import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_CAPTURE_QUERY_PARAMETERS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

@WithConfig(key = JDBC_CAPTURE_QUERY_PARAMETERS, value = "true")
class QueryParametersCaptureForkedTest extends QueryParametersTestSupport {

  @Test
  void statementsGetCaptureState() throws SQLException {
    assertTrue(
        new PreparedStatementInstrumentation()
            .contextStore()
            .containsKey("java.sql.PreparedStatement"));
    try (PreparedStatement statement = h2.prepareStatement("SELECT 1")) {
      assertEquals(1, injectedContextFields(statement.getClass()).size());
    }
  }

  @Test
  void positionalParametersUseZeroBasedKeys() throws SQLException {
    try (PreparedStatement statement =
        h2.prepareStatement("SELECT * FROM orders WHERE id = ? AND status = ?")) {
      statement.setLong(1, 48213L);
      statement.setString(2, "SHIPPED");
      statement.executeQuery().close();
    }

    assertEquals(
        tags(
            "db.query.parameter.0", "48213",
            "_dd.dbm.query.parameter_type.0", "BIGINT",
            "db.query.parameter.1", "SHIPPED",
            "_dd.dbm.query.parameter_type.1", "VARCHAR"),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void typesComeFromTheSetterTheSqlTypeOrTheJavaClass() throws SQLException {
    Timestamp created = Timestamp.valueOf("2026-01-02 03:04:05.0");
    try (PreparedStatement statement = h2.prepareStatement("SELECT ?, ?, ?, ?, ?, ?, ?, ?")) {
      statement.setInt(1, 7);
      statement.setObject(2, "8", Types.BIGINT);
      statement.setObject(3, new BigDecimal("9.5"));
      statement.setNull(4, Types.VARCHAR);
      statement.setObject(5, null);
      statement.setBytes(6, new byte[] {0x0A, (byte) 0xFF});
      statement.setNString(7, "x");
      statement.setTimestamp(8, created);
      statement.executeQuery().close();
    }

    Map<String, Object> tags = parameterTags(preparedStatementSpans(1).get(0));
    assertEquals("7", tags.get("db.query.parameter.0"));
    assertEquals("INTEGER", tags.get("_dd.dbm.query.parameter_type.0"));
    assertEquals("8", tags.get("db.query.parameter.1"));
    assertEquals("BIGINT", tags.get("_dd.dbm.query.parameter_type.1"));
    assertEquals("9.5", tags.get("db.query.parameter.2"));
    assertEquals("NUMERIC", tags.get("_dd.dbm.query.parameter_type.2"));
    assertEquals("null", tags.get("db.query.parameter.3"));
    assertEquals("VARCHAR", tags.get("_dd.dbm.query.parameter_type.3"));
    assertEquals("null", tags.get("db.query.parameter.4"));
    assertEquals("NULL", tags.get("_dd.dbm.query.parameter_type.4"));
    assertEquals("0AFF", tags.get("db.query.parameter.5"));
    assertEquals("VARBINARY", tags.get("_dd.dbm.query.parameter_type.5"));
    assertEquals("x", tags.get("db.query.parameter.6"));
    assertEquals("NVARCHAR", tags.get("_dd.dbm.query.parameter_type.6"));
    assertEquals(created.toString(), tags.get("db.query.parameter.7"));
    assertEquals("TIMESTAMP", tags.get("_dd.dbm.query.parameter_type.7"));
    assertEquals(null, tags.get(TRUNCATED_TAG));
  }

  @Test
  void arbitraryPrecisionIntegersAreNumeric() throws SQLException {
    BigInteger large = new BigInteger("999999999999999999999999999999");
    try (PreparedStatement statement = h2.prepareStatement("SELECT ?")) {
      statement.setObject(1, large);
      statement.executeQuery().close();
    }

    assertEquals(
        tags(
            "db.query.parameter.0", "999999999999999999999999999999",
            "_dd.dbm.query.parameter_type.0", "NUMERIC"),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void sqlTypeObjectsGiveTheirName() {
    // not every driver implements setObject with a SQLType
    QueryParameters queryParameters = new QueryParameters();
    queryParameters.bindWithSqlType(1, "x", JDBCType.NVARCHAR);
    AgentSpan span = startSpan("test", "query");
    queryParameters.applyTo(span);
    span.finish();

    assertEquals("x", span.getTag("db.query.parameter.0"));
    assertEquals("NVARCHAR", span.getTag("_dd.dbm.query.parameter_type.0"));
  }

  @Test
  void longValuesAreTruncated() throws SQLException {
    StringBuilder status = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      status.append((char) ('a' + i % 26));
    }
    try (PreparedStatement statement =
        h2.prepareStatement("SELECT * FROM orders WHERE status = ?")) {
      statement.setString(1, status.toString());
      statement.executeQuery().close();
    }

    Map<String, Object> tags = parameterTags(preparedStatementSpans(1).get(0));
    assertEquals(status.substring(0, 1024), tags.get("db.query.parameter.0"));
    assertEquals(true, tags.get(TRUNCATED_TAG));
  }

  @Test
  void parametersBeyondTheLimitAreDropped() throws SQLException {
    StringBuilder sql = new StringBuilder("SELECT * FROM orders WHERE id IN (?");
    for (int i = 1; i < 65; i++) {
      sql.append(", ?");
    }
    try (PreparedStatement statement = h2.prepareStatement(sql.append(')').toString())) {
      for (int i = 1; i <= 65; i++) {
        statement.setLong(i, i);
      }
      statement.executeQuery().close();
    }

    Map<String, Object> tags = parameterTags(preparedStatementSpans(1).get(0));
    assertEquals("64", tags.get("db.query.parameter.63"));
    assertEquals(null, tags.get("db.query.parameter.64"));
    assertEquals(true, tags.get(TRUNCATED_TAG));
    assertEquals(64 * 2 + 1, tags.size());
  }

  @Test
  void streamsAndLobsKeepOnlyTheirType() throws SQLException {
    try (PreparedStatement statement =
        h2.prepareStatement("INSERT INTO orders (id, payload, notes) VALUES (?, ?, ?)")) {
      statement.setLong(1, 1L);
      statement.setBinaryStream(2, new ByteArrayInputStream("secret".getBytes(UTF_8)));
      statement.setClob(3, new StringReader("secret"));
      statement.executeUpdate();
    }

    assertEquals(
        tags(
            "db.query.parameter.0",
            "1",
            "_dd.dbm.query.parameter_type.0",
            "BIGINT",
            "_dd.dbm.query.parameter_type.1",
            "LONGVARBINARY",
            "_dd.dbm.query.parameter_type.2",
            "CLOB",
            TRUNCATED_TAG,
            true),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void clearParametersForgetsTheValues() throws SQLException {
    try (PreparedStatement statement =
        h2.prepareStatement("SELECT * FROM orders WHERE id = ? AND status = ?")) {
      statement.setLong(1, 1L);
      statement.setString(2, "NEW");
      statement.clearParameters();
      statement.setLong(1, 2L);
      // the driver rejects the statement since the second parameter is not set anymore
      assertThrows(SQLException.class, statement::executeQuery);
    }

    assertEquals(
        tags("db.query.parameter.0", "2", "_dd.dbm.query.parameter_type.0", "BIGINT"),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void reusedStatementReportsTheValuesOfEachExecution() throws SQLException {
    try (PreparedStatement statement =
        h2.prepareStatement("UPDATE orders SET status = ? WHERE id = ?")) {
      statement.setString(1, "NEW");
      statement.setLong(2, 1L);
      statement.executeUpdate();
      statement.setString(1, "SHIPPED");
      statement.executeUpdate();
    }

    List<DDSpan> spans = preparedStatementSpans(2);
    assertEquals("NEW", spans.get(0).getTag("db.query.parameter.0"));
    assertEquals("1", spans.get(0).getTag("db.query.parameter.1"));
    assertEquals("SHIPPED", spans.get(1).getTag("db.query.parameter.0"));
    assertEquals("1", spans.get(1).getTag("db.query.parameter.1"));
  }

  @Test
  void batchesAreNotCaptured() throws SQLException {
    try (PreparedStatement statement =
        h2.prepareStatement("INSERT INTO orders (id, status) VALUES (?, ?)")) {
      statement.setLong(1, 1L);
      statement.setString(2, "NEW");
      statement.addBatch();
      statement.setLong(1, 2L);
      statement.setString(2, "NEW");
      statement.addBatch();
      statement.executeBatch();
    }

    assertEquals(new TreeMap<>(), parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void valuesThatCannotBeTurnedIntoStringsAreLeftOut() throws SQLException {
    // a PGobject holding SQL NULL returns null from toString
    PGobject json = new PGobject();
    json.setType("json");
    try (PreparedStatement statement = h2.prepareStatement("SELECT ?, ?")) {
      statement.setObject(1, json);
      statement.setLong(2, 1L);
      statement.executeQuery().close();
    }

    assertEquals(
        tags(
            "_dd.dbm.query.parameter_type.0",
            "JAVA_OBJECT",
            "db.query.parameter.1",
            "1",
            "_dd.dbm.query.parameter_type.1",
            "BIGINT",
            TRUNCATED_TAG,
            true),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void valuesChangedAfterBindingAreReportedAsBound() throws SQLException {
    byte[] bytes = {1, 2};
    Timestamp timestamp = Timestamp.valueOf("2026-01-02 03:04:05.0");
    Date date = Date.valueOf("2026-01-02");
    StringBuilder status = new StringBuilder("NEW");
    try (PreparedStatement statement = h2.prepareStatement("SELECT ?, ?, ?, ?")) {
      statement.setBytes(1, bytes);
      statement.setTimestamp(2, timestamp);
      statement.setDate(3, date);
      statement.setObject(4, status);
      bytes[0] = 3;
      timestamp.setTime(0L);
      date.setTime(0L);
      status.setLength(0);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        // the database got the values as they were bound
        assertArrayEquals(new byte[] {1, 2}, resultSet.getBytes(1));
        assertEquals(Timestamp.valueOf("2026-01-02 03:04:05.0"), resultSet.getTimestamp(2));
      }
    }

    Map<String, Object> tags = parameterTags(preparedStatementSpans(1).get(0));
    assertEquals("0102", tags.get("db.query.parameter.0"));
    assertEquals("2026-01-02 03:04:05.0", tags.get("db.query.parameter.1"));
    assertEquals("2026-01-02", tags.get("db.query.parameter.2"));
    assertEquals("NEW", tags.get("db.query.parameter.3"));
  }

  @Test
  void valuesBoundBeforeABatchAreLeftOutAfterIt() throws SQLException {
    try (PreparedStatement statement = h2.prepareStatement("INSERT INTO orders (id) VALUES (?)")) {
      statement.setLong(1, 1L);
      statement.addBatch();
      statement.setLong(1, 2L);
      statement.executeBatch();
      // the driver may run with a value of the batch rather than 2
      statement.executeUpdate();
      statement.setLong(1, 3L);
      statement.executeUpdate();
      statement.clearParameters();
      statement.setLong(1, 4L);
      statement.executeUpdate();
    }

    List<DDSpan> spans = preparedStatementSpans(4);
    assertEquals(new TreeMap<>(), parameterTags(spans.get(0)));
    assertEquals(tags(TRUNCATED_TAG, true), parameterTags(spans.get(1)));
    assertEquals(
        tags(
            "db.query.parameter.0",
            "3",
            "_dd.dbm.query.parameter_type.0",
            "BIGINT",
            TRUNCATED_TAG,
            true),
        parameterTags(spans.get(2)));
    assertEquals(
        tags("db.query.parameter.0", "4", "_dd.dbm.query.parameter_type.0", "BIGINT"),
        parameterTags(spans.get(3)));
  }

  @Test
  void valuesBoundBeforeAFailedBatchAreLeftOutAfterIt() throws SQLException {
    try (PreparedStatement statement = h2.prepareStatement("INSERT INTO orders (id) VALUES (?)")) {
      statement.setString(1, "not a number");
      statement.addBatch();
      assertThrows(SQLException.class, statement::executeBatch);
      statement.setLong(1, 1L);
      statement.executeUpdate();
    }

    List<DDSpan> spans = preparedStatementSpans(2);
    assertEquals(
        tags(
            "db.query.parameter.0",
            "1",
            "_dd.dbm.query.parameter_type.0",
            "BIGINT",
            TRUNCATED_TAG,
            true),
        parameterTags(spans.get(1)));
  }

  @Test
  void namedParametersUseTheirName() throws SQLException {
    try (Connection hsqldb = shipProcedureDatabase("qparams_named")) {
      try (CallableStatement call = hsqldb.prepareCall("{call ship(?, ?)}")) {
        call.setLong("ORDER_ID", 48213L);
        call.setString("NEW_STATUS", "SHIPPED");
        call.execute();
      }
    }

    // only the names are recorded, even if the driver implements the named setters with the
    // positional ones
    assertEquals(
        tags(
            "db.query.parameter.ORDER_ID", "48213",
            "_dd.dbm.query.parameter_type.ORDER_ID", "BIGINT",
            "db.query.parameter.NEW_STATUS", "SHIPPED",
            "_dd.dbm.query.parameter_type.NEW_STATUS", "VARCHAR"),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void bindingByPositionAfterNameLeavesTheNamesOut() throws SQLException {
    try (Connection hsqldb = shipProcedureDatabase("qparams_named_then_positional")) {
      try (CallableStatement call = hsqldb.prepareCall("{call ship(?, ?)}")) {
        call.setLong("ORDER_ID", 1L);
        call.setString("NEW_STATUS", "SHIPPED");
        // the same parameter as ORDER_ID
        call.setLong(1, 2L);
        call.execute();
      }
    }

    // NEW_STATUS is still bound, but a name and a position can't be matched
    assertEquals(
        tags(
            "db.query.parameter.0",
            "2",
            "_dd.dbm.query.parameter_type.0",
            "BIGINT",
            TRUNCATED_TAG,
            true),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void bindingByNameAfterPositionLeavesThePositionsOut() throws SQLException {
    try (Connection hsqldb = shipProcedureDatabase("qparams_positional_then_named")) {
      try (CallableStatement call = hsqldb.prepareCall("{call ship(?, ?)}")) {
        call.setLong(1, 1L);
        call.setString(2, "SHIPPED");
        // the same parameter as position 1
        call.setLong("ORDER_ID", 2L);
        call.execute();
      }
    }

    assertEquals(
        tags(
            "db.query.parameter.ORDER_ID",
            "2",
            "_dd.dbm.query.parameter_type.ORDER_ID",
            "BIGINT",
            TRUNCATED_TAG,
            true),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void namesThatMayBeTheSameParameterLeaveTheEarlierValueOut() throws SQLException {
    try (Connection hsqldb = shipProcedureDatabase("qparams_name_aliases")) {
      try (CallableStatement call = hsqldb.prepareCall("{call ship(?, ?)}")) {
        call.setLong("ORDER_ID", 1L);
        call.setString("NEW_STATUS", "SHIPPED");
        // HSQLDB ignores the case, so this replaces ORDER_ID
        call.setLong("order_id", 2L);
        call.execute();
      }
      try (Statement query = hsqldb.createStatement();
          ResultSet shipped = query.executeQuery("SELECT id FROM shipments")) {
        shipped.next();
        assertEquals(2L, shipped.getLong(1));
      }
    }

    assertEquals(
        tags(
            "db.query.parameter.NEW_STATUS",
            "SHIPPED",
            "_dd.dbm.query.parameter_type.NEW_STATUS",
            "VARCHAR",
            "db.query.parameter.order_id",
            "2",
            "_dd.dbm.query.parameter_type.order_id",
            "BIGINT",
            TRUNCATED_TAG,
            true),
        parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void inoutParametersAreLeftOutAfterTheExecution() throws SQLException {
    try (Connection hsqldb =
        DriverManager.getConnection("jdbc:hsqldb:mem:qparams_inout", "sa", "")) {
      try (Statement statement = hsqldb.createStatement()) {
        statement.execute(
            "CREATE PROCEDURE increment(INOUT counter BIGINT) "
                + "BEGIN ATOMIC SET counter = counter + 1; END");
      }
      writer.clear();
      try (CallableStatement call = hsqldb.prepareCall("{call increment(?)}")) {
        call.setLong(1, 1L);
        call.registerOutParameter(1, Types.BIGINT);
        call.execute();
        assertEquals(2L, call.getLong(1));
        // runs with 2, the value the first execution put in the parameter
        call.execute();
        assertEquals(3L, call.getLong(1));
      }
    }

    List<DDSpan> spans = preparedStatementSpans(2);
    assertEquals(
        tags("db.query.parameter.0", "1", "_dd.dbm.query.parameter_type.0", "BIGINT"),
        parameterTags(spans.get(0)));
    assertEquals(tags(TRUNCATED_TAG, true), parameterTags(spans.get(1)));
  }

  /** Opens an HSQLDB database with a {@code ship} procedure, skipping drivers without names. */
  private Connection shipProcedureDatabase(String name) throws SQLException {
    Connection hsqldb = DriverManager.getConnection("jdbc:hsqldb:mem:" + name, "sa", "");
    DatabaseMetaData metaData = hsqldb.getMetaData();
    boolean supportsNames =
        metaData.getDriverMajorVersion() > 2 || metaData.getDriverMinorVersion() >= 5;
    if (!supportsNames) {
      hsqldb.close();
    }
    assumeTrue(
        supportsNames, "HSQLDB supports named parameters from 2.5, run by latestDepForkedTest");
    try (Statement statement = hsqldb.createStatement()) {
      statement.execute("CREATE TABLE shipments (id BIGINT, status VARCHAR(20))");
      statement.execute(
          "CREATE PROCEDURE ship(IN order_id BIGINT, IN new_status VARCHAR(20)) "
              + "MODIFIES SQL DATA INSERT INTO shipments VALUES (order_id, new_status)");
    }
    writer.clear();
    return hsqldb;
  }

  private static Map<String, Object> tags(Object... keysAndValues) {
    Map<String, Object> tags = new HashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      tags.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return new TreeMap<>(tags);
  }
}
