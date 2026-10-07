package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Applications may pass null SQL to {@link Statement}; the driver rejects it, we must not leak. */
class NullSqlStatementTest extends AbstractInstrumentationTest {

  @Test
  void executeNullSqlThroughPoolProducesFinishedErrorSpan() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:nullSqlStatementTest");
    config.setMaximumPoolSize(1);
    try (HikariDataSource dataSource = new HikariDataSource(config);
        Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      // drop traces from the pool's own setup queries
      writer.clear();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope scope = activateSpan(parent)) {
        assertThrows(Exception.class, () -> statement.execute(null));
      }
      parent.finish();
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .resourceName(name -> "DB Query".contentEquals(name))
                .type("sql")
                .error()));
  }
}
