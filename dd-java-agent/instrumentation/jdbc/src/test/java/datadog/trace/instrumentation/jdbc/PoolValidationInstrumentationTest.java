package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Public so H2 can call {@link #ping()}.
 *
 * <p>Assertions look for specific spans rather than whole trace shapes: other tests in the same JVM
 * can leave pool classes transformed to emit connection spans.
 */
public class PoolValidationInstrumentationTest extends AbstractInstrumentationTest {
  private static final String URL = "jdbc:h2:mem:pool_validation;DB_CLOSE_DELAY=-1";

  /** Validation query whose calls are counted, so a test can't pass without validating. */
  private static final String VALIDATION_QUERY = "SELECT PING()";

  private static final AtomicInteger PINGS = new AtomicInteger();

  /** Called by H2 for {@code PING()}. */
  public static int ping() {
    PINGS.incrementAndGet();
    return 1;
  }

  @BeforeAll
  static void setUpDatabase() throws Exception {
    Class.forName("org.h2.Driver");
    try (Connection connection = DriverManager.getConnection(URL);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE ALIAS IF NOT EXISTS PING FOR \""
              + PoolValidationInstrumentationTest.class.getName()
              + ".ping\"");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hikari", "dbcp2", "tomcat", "c3p0"})
  void validationQueryCreatesNoSpans(String pool) throws Exception {
    try (Pool p = createPool(pool)) {
      DataSource ds = p.dataSource;
      // warm up so the traced borrow reuses an idle connection, which the pool must validate
      ds.getConnection().close();
      Thread.sleep(10);

      int pingsBefore = PINGS.get();
      runUnderTrace(() -> {
        try (Connection connection = ds.getConnection();
            Statement statement = connection.createStatement()) {
          statement.execute("SELECT 2 AS app_query");
        }
      });
      assertTrue(PINGS.get() > pingsBefore, "the pool didn't validate on borrow");
    }

    // the pool is closed, so no validation from a background thread can arrive late
    assertChildOfParent("SELECT ? AS app_query");
    for (DDSpan span : allSpans()) {
      assertFalse(
          span.getResourceName().toString().contains("PING"), "validation query traced: " + span);
    }
  }

  @Test
  void tomcatInitSqlStaysTraced() throws Exception {
    org.apache.tomcat.jdbc.pool.DataSource ds = tomcatDataSource();
    ds.setInitSQL("SELECT 3 AS init_sql");
    try {
      runUnderTrace(() -> ds.getConnection().close());
    } finally {
      ds.close();
    }

    assertChildOfParent("SELECT ? AS init_sql");
  }

  /** Waits for the span with {@code resource} and checks it is a child of the test's parent. */
  private void assertChildOfParent(String resource) {
    blockUntilTracesMatch(traces -> find(resource) != null);
    DDSpan parent = find("parent");
    DDSpan span = find(resource);
    assertEquals("sql", span.getSpanType());
    assertEquals(parent.getSpanId(), span.getParentId(), "not a child of the parent: " + span);
  }

  private DDSpan find(String resource) {
    for (DDSpan span : allSpans()) {
      if (resource.contentEquals(span.getResourceName())) {
        return span;
      }
    }
    return null;
  }

  private List<DDSpan> allSpans() {
    List<DDSpan> spans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      spans.addAll(trace);
    }
    return spans;
  }

  private static Pool createPool(String pool) throws Exception {
    switch (pool) {
      case "hikari":
        // Hikari skips the liveness check for a recently used connection; 2.4.0 reads the
        // property without the Ms suffix. The test extension restores system properties.
        System.setProperty("com.zaxxer.hikari.aliveBypassWindowMs", "0");
        System.setProperty("com.zaxxer.hikari.aliveBypassWindow", "0");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setConnectionTestQuery(VALIDATION_QUERY);
        config.setMaximumPoolSize(1);
        HikariDataSource hikari = new HikariDataSource(config);
        return new Pool(hikari, hikari::close);
      case "dbcp2":
        BasicDataSource dbcp2 = new BasicDataSource();
        dbcp2.setUrl(URL);
        dbcp2.setValidationQuery(VALIDATION_QUERY);
        dbcp2.setTestOnBorrow(true);
        dbcp2.setMaxTotal(1);
        return new Pool(dbcp2, dbcp2::close);
      case "tomcat":
        org.apache.tomcat.jdbc.pool.DataSource tomcat = tomcatDataSource();
        tomcat.setValidationQuery(VALIDATION_QUERY);
        tomcat.setTestOnBorrow(true);
        tomcat.setValidationInterval(0);
        return new Pool(tomcat, tomcat::close);
      case "c3p0":
        ComboPooledDataSource c3p0 = new ComboPooledDataSource();
        c3p0.setDriverClass("org.h2.Driver");
        c3p0.setJdbcUrl(URL);
        c3p0.setPreferredTestQuery(VALIDATION_QUERY);
        c3p0.setTestConnectionOnCheckout(true);
        c3p0.setMinPoolSize(1);
        c3p0.setInitialPoolSize(1);
        c3p0.setMaxPoolSize(1);
        return new Pool(c3p0, c3p0::close);
      default:
        throw new IllegalArgumentException(pool);
    }
  }

  private static org.apache.tomcat.jdbc.pool.DataSource tomcatDataSource() {
    org.apache.tomcat.jdbc.pool.DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource();
    ds.setUrl(URL);
    ds.setDriverClassName("org.h2.Driver");
    ds.setInitialSize(0);
    ds.setMinIdle(0);
    ds.setMaxActive(1);
    return ds;
  }

  private static final class Pool implements AutoCloseable {
    final DataSource dataSource;
    final AutoCloseable closer;

    Pool(DataSource dataSource, AutoCloseable closer) {
      this.dataSource = dataSource;
      this.closer = closer;
    }

    @Override
    public void close() throws Exception {
      closer.close();
    }
  }

  @FunctionalInterface
  private interface Body {
    void run() throws Exception;
  }

  private static void runUnderTrace(Body body) throws Exception {
    AgentSpan span = startSpan("test", "parent");
    try (ContextScope scope = activateSpan(span)) {
      body.run();
    } finally {
      span.finish();
    }
  }
}
