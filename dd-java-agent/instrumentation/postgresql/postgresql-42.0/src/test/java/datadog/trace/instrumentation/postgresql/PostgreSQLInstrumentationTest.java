package datadog.trace.instrumentation.postgresql;

import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.includes;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNonNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

abstract class PostgreSQLInstrumentationTest extends AbstractInstrumentationTest {

  static PostgreSQLContainer<?> container;
  static int port;
  static Connection connection;

  abstract String service();

  abstract String operation();

  @BeforeAll
  static void setupAll() throws Exception {
    container =
        new PostgreSQLContainer<>("postgres:13-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withStartupTimeout(Duration.ofMinutes(3));
    container.start();
    port = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);

    // Connect with retries to handle port forwarding delays (e.g. Colima)
    int maxRetries = 10;
    for (int i = 0; i < maxRetries; i++) {
      try {
        connection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        break;
      } catch (SQLException e) {
        if (i == maxRetries - 1) {
          throw e;
        }
        Thread.sleep(2000);
      }
    }

    // Set up the test schema
    Statement stmt = connection.createStatement();
    stmt.execute(
        "CREATE TABLE IF NOT EXISTS test_table (id SERIAL PRIMARY KEY, name VARCHAR(100))");
    stmt.execute("INSERT INTO test_table (name) VALUES ('alice')");
    stmt.execute("INSERT INTO test_table (name) VALUES ('bob')");
    stmt.close();

    // Allow time for any setup traces to be written, then clear for actual tests
    Thread.sleep(1000);
    writer.start();
  }

  @AfterAll
  static void cleanupAll() throws Exception {
    if (connection != null) {
      connection.close();
    }
    if (container != null) {
      container.stop();
    }
  }

  @Test
  void executeQueryCreatesSpan() throws Exception {
    Statement stmt = connection.createStatement();
    try {
      ResultSet rs = stmt.executeQuery("SELECT * FROM test_table");
      rs.next();
      rs.close();
    } finally {
      stmt.close();
    }

    assertTraces(trace(postgresSpan("SELECT * FROM test_table", "SELECT", false, false, false)));
  }

  @Test
  void executeUpdateCreatesSpan() throws Exception {
    Statement stmt = connection.createStatement();
    try {
      int count = stmt.executeUpdate("INSERT INTO test_table (name) VALUES ('charlie')");
      assert count == 1;
    } finally {
      stmt.close();
    }

    assertTraces(
        trace(
            postgresSpan(
                "INSERT INTO test_table (name) VALUES (?)", "INSERT", false, false, false)));
  }

  @Test
  void executeCreatesSpan() throws Exception {
    Statement stmt = connection.createStatement();
    try {
      stmt.execute("SELECT 1");
    } finally {
      stmt.close();
    }

    assertTraces(trace(postgresSpan("SELECT ?", "SELECT", false, false, false)));
  }

  @Test
  void executeBatchCreatesSpan() throws Exception {
    Statement stmt = connection.createStatement();
    try {
      stmt.addBatch("INSERT INTO test_table (name) VALUES ('batch1')");
      stmt.addBatch("INSERT INTO test_table (name) VALUES ('batch2')");
      int[] results = stmt.executeBatch();
      assert results.length == 2;
    } finally {
      stmt.close();
    }

    assertTraces(
        trace(
            postgresSpan(
                "INSERT INTO test_table (name) VALUES (?)", "INSERT", false, false, false)));
  }

  @Test
  void preparedStatementExecuteQueryCreatesSpan() throws Exception {
    PreparedStatement pstmt =
        connection.prepareStatement("SELECT * FROM test_table WHERE name = ?");
    try {
      pstmt.setString(1, "alice");
      ResultSet rs = pstmt.executeQuery();
      rs.next();
      assert "alice".equals(rs.getString("name"));
      rs.close();
    } finally {
      pstmt.close();
    }

    assertTraces(
        trace(
            postgresSpan(
                "SELECT * FROM test_table WHERE name = ?", "SELECT", false, false, false)));
  }

  @Test
  void preparedStatementExecuteUpdateCreatesSpan() throws Exception {
    PreparedStatement pstmt =
        connection.prepareStatement("INSERT INTO test_table (name) VALUES (?)");
    try {
      pstmt.setString(1, "prepared_insert");
      int count = pstmt.executeUpdate();
      assert count == 1;
    } finally {
      pstmt.close();
    }

    assertTraces(
        trace(
            postgresSpan(
                "INSERT INTO test_table (name) VALUES (?)", "INSERT", false, false, false)));
  }

  @Test
  void preparedStatementExecuteCreatesSpan() throws Exception {
    PreparedStatement pstmt = connection.prepareStatement("SELECT * FROM test_table WHERE id = ?");
    try {
      pstmt.setInt(1, 1);
      pstmt.execute();
    } finally {
      pstmt.close();
    }

    assertTraces(
        trace(
            postgresSpan("SELECT * FROM test_table WHERE id = ?", "SELECT", false, false, false)));
  }

  @Test
  void preparedStatementExecuteBatchCreatesSpan() throws Exception {
    PreparedStatement pstmt =
        connection.prepareStatement("INSERT INTO test_table (name) VALUES (?)");
    try {
      pstmt.setString(1, "batch_prepared1");
      pstmt.addBatch();
      pstmt.setString(1, "batch_prepared2");
      pstmt.addBatch();
      int[] results = pstmt.executeBatch();
      assert results.length == 2;
    } finally {
      pstmt.close();
    }

    assertTraces(
        trace(
            postgresSpan(
                "INSERT INTO test_table (name) VALUES (?)", "INSERT", false, false, false)));
  }

  @Test
  void queryUnderParentSpan() throws Exception {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope scope = activateSpan(parentSpan);
    try {
      Statement stmt = connection.createStatement();
      stmt.executeQuery("SELECT * FROM test_table");
      stmt.close();
    } finally {
      scope.close();
      parentSpan.finish();
    }

    assertTraces(
        trace(
            TraceMatcher.SORT_BY_START_TIME,
            SpanMatcher.span()
                .serviceNameDefined()
                .operationName("parent")
                .resourceName("parent")
                .root(),
            postgresSpan("SELECT * FROM test_table", "SELECT", false, false, false)
                .childOfPrevious()));
  }

  @Test
  void errorDuringQuery() throws Exception {
    Statement stmt = connection.createStatement();
    try {
      stmt.executeQuery("SELECT * FROM non_existent_table");
    } catch (SQLException expected) {
      // expected
    } finally {
      stmt.close();
    }

    assertTraces(
        trace(postgresSpan("SELECT * FROM non_existent_table", "SELECT", true, false, false)));
  }

  @Test
  void errorDuringExecuteUpdate() throws Exception {
    Statement stmt = connection.createStatement();
    try {
      stmt.executeUpdate("INSERT INTO non_existent_table (name) VALUES ('fail')");
    } catch (SQLException expected) {
      // expected
    } finally {
      stmt.close();
    }

    assertTraces(
        trace(
            postgresSpan(
                "INSERT INTO non_existent_table (name) VALUES (?)", "INSERT", true, false, false)));
  }

  @Test
  void splitByInstance() throws Exception {
    WithConfigExtension.injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true");
    Statement stmt = connection.createStatement();
    try {
      stmt.executeQuery("SELECT * FROM test_table");
    } finally {
      stmt.close();
    }

    assertTraces(trace(postgresSpan("SELECT * FROM test_table", "SELECT", false, true, false)));
  }

  @Test
  void dbmServiceModeInjectsCommentAndSetsTag() throws Exception {
    WithConfigExtension.injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "service");
    Statement stmt = connection.createStatement();
    try {
      ResultSet rs = stmt.executeQuery("SELECT * FROM test_table");
      rs.next();
      rs.close();
    } finally {
      stmt.close();
    }

    assertTraces(trace(postgresSpan("SELECT * FROM test_table", "SELECT", false, false, true)));
  }

  @Test
  void dbmFullModeInjectsCommentWithTraceparent() throws Exception {
    WithConfigExtension.injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "full");
    Statement stmt = connection.createStatement();
    try {
      ResultSet rs = stmt.executeQuery("SELECT * FROM test_table");
      rs.next();
      rs.close();
    } finally {
      stmt.close();
    }

    assertTraces(trace(postgresSpan("SELECT * FROM test_table", "SELECT", false, false, true)));
  }

  @Test
  void dbmServiceModeInjectsCommentForPreparedStatement() throws Exception {
    WithConfigExtension.injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "service");
    PreparedStatement pstmt =
        connection.prepareStatement("SELECT * FROM test_table WHERE name = ?");
    try {
      pstmt.setString(1, "alice");
      ResultSet rs = pstmt.executeQuery();
      rs.next();
      assert "alice".equals(rs.getString("name"));
      rs.close();
    } finally {
      pstmt.close();
    }

    assertTraces(
        trace(
            postgresSpan("SELECT * FROM test_table WHERE name = ?", "SELECT", false, false, true)));
  }

  @Test
  void dbmFullModeInjectsCommentForPreparedStatement() throws Exception {
    WithConfigExtension.injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "full");
    PreparedStatement pstmt =
        connection.prepareStatement("SELECT * FROM test_table WHERE name = ?");
    try {
      pstmt.setString(1, "alice");
      ResultSet rs = pstmt.executeQuery();
      rs.next();
      assert "alice".equals(rs.getString("name"));
      rs.close();
    } finally {
      pstmt.close();
    }

    assertTraces(
        trace(
            postgresSpan("SELECT * FROM test_table WHERE name = ?", "SELECT", false, false, true)));
  }

  @Test
  void dbmDisabledDoesNotInjectCommentOrSetTag() throws Exception {
    WithConfigExtension.injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "disabled");
    Statement stmt = connection.createStatement();
    try {
      ResultSet rs = stmt.executeQuery("SELECT * FROM test_table");
      rs.next();
      rs.close();
    } finally {
      stmt.close();
    }

    assertTraces(trace(postgresSpan("SELECT * FROM test_table", "SELECT", false, false, false)));
  }

  SpanMatcher postgresSpan(
      String resource,
      String dbOperation,
      boolean hasError,
      boolean renameService,
      boolean addDbmTag) {
    List<TagsMatcher> tagMatchers = new ArrayList<>();
    tagMatchers.add(defaultTags());
    tagMatchers.add(tag(Tags.COMPONENT, is("java-postgresql")));
    tagMatchers.add(tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)));
    tagMatchers.add(tag(Tags.DB_TYPE, is("postgresql")));
    tagMatchers.add(tag(Tags.DB_INSTANCE, is("testdb")));
    tagMatchers.add(tag(Tags.DB_USER, is("testuser")));
    tagMatchers.add(tag(Tags.DB_OPERATION, is(dbOperation)));
    tagMatchers.add(tag(Tags.PEER_HOSTNAME, isNonNull()));
    tagMatchers.add(tag(Tags.PEER_PORT, isNonNull()));
    if (hasError) {
      tagMatchers.add(error(SQLException.class));
      tagMatchers.add(tag(DDTags.ERROR_MSG, isNonNull()));
    }
    if (addDbmTag) {
      tagMatchers.add(tag(InstrumentationTags.DBM_TRACE_INJECTED, is(true)));
    }
    // Peer service tags
    tagMatchers.add(includes(DDTags.PEER_SERVICE_SOURCE));
    tagMatchers.add(tag("peer.service", any()));
    tagMatchers.add(tag(DDTags.DD_SVC_SRC, any()));

    return SpanMatcher.span()
        .serviceName(renameService ? "testdb" : service())
        .operationName(operation())
        .resourceName(resource)
        .type(DDSpanTypes.SQL)
        .error(hasError)
        .root()
        .tags(tagMatchers.toArray(new TagsMatcher[0]));
  }
}

@WithConfig(key = TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v0")
class PostgreSQLInstrumentationV0Test extends PostgreSQLInstrumentationTest {

  @Override
  String service() {
    return "postgresql";
  }

  @Override
  String operation() {
    return "postgresql.query";
  }
}

@WithConfig(key = TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v1")
class PostgreSQLInstrumentationV1ForkedTest extends PostgreSQLInstrumentationTest {

  @Override
  String service() {
    return Config.get().getServiceName();
  }

  @Override
  String operation() {
    return "postgresql.query";
  }
}
