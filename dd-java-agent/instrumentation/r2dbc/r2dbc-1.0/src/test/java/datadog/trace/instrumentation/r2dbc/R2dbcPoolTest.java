package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcInstrumentationTest.H2_QUERY;
import static datadog.trace.instrumentation.r2dbc.R2dbcInstrumentationTest.eqs;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pooled connections via an {@code r2dbc:pool:} URL. The pool provider resolves its delegate with a
 * nested {@code ConnectionFactories.find}, so this guards against double wrapping (two spans per
 * query) and checks the db type comes from the pooled protocol, not the {@code pool} driver.
 */
class R2dbcPoolTest extends AbstractInstrumentationTest {

  private ConnectionFactory connectionFactory;
  private Connection connection;

  @BeforeEach
  public void setUp() {
    connectionFactory = ConnectionFactories.get("r2dbc:pool:h2:mem:///pooldb;DB_CLOSE_DELAY=-1");
    connection = Mono.from(connectionFactory.create()).block();
    Mono.from(
            connection.createStatement("CREATE TABLE IF NOT EXISTS pool_table (id INT)").execute())
        .flatMapMany(result -> result.getRowsUpdated())
        .blockLast();

    tracer.flush();
    writer.clear();
  }

  @AfterEach
  public void tearDown() {
    if (connection != null) {
      Mono.from(connection.close()).block();
    }
  }

  @Test
  void poolFactoryKeepsItsTypeAndDisposes() {
    // The outer pool factory must not be replaced by a proxy: applications cast to ConnectionPool
    // and rely on its disposal API.
    assertTrue(connectionFactory instanceof ConnectionPool, connectionFactory.getClass().getName());
    ConnectionPool pool = (ConnectionPool) connectionFactory;
    Mono.from(connection.close()).block();
    connection = null;
    pool.dispose();
    assertTrue(pool.isDisposed());
  }

  @Test
  void pooledQueryCreatesExactlyOneSpan() {
    AgentSpan parent = startSpan("test", "parent");
    try (ContextScope scope = activateSpan(parent)) {
      Flux.from(connection.createStatement("SELECT * FROM pool_table").execute())
          .flatMap(result -> result.map((row, metadata) -> row.get(0)))
          .collectList()
          .block();
    } finally {
      parent.finish();
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(H2_QUERY)
                .resourceName(eqs("SELECT * FROM pool_table"))
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_OPERATION, eqs("SELECT")),
                    tag(Tags.DB_INSTANCE, any()),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }
}
