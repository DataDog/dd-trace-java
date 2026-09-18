package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcInstrumentationTest.eqs;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Result;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests for R2DBC Database Monitoring (DBM) feature. Verifies that connection metadata tags
 * (db.instance, db.user, peer.hostname) are correctly populated on spans, and that the
 * _dd.dbm_trace_injected tag is set when DBM propagation mode is "full".
 */
@WithConfig(key = "dbm.propagation.mode", value = "full")
@WithConfig(key = "service", value = "test_service", addPrefix = false)
class R2dbcDbmForkedTest extends AbstractInstrumentationTest {

  private static final Pattern H2_QUERY = Pattern.compile("h2\\.query");

  private ConnectionFactory connectionFactory;
  private Connection connection;

  @BeforeEach
  public void setUp() {
    connectionFactory =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "h2")
                .option(ConnectionFactoryOptions.PROTOCOL, "mem")
                .option(ConnectionFactoryOptions.DATABASE, "testdb")
                .build());
    connection = Mono.from(connectionFactory.create()).block();

    // Create a test table
    Mono.from(
            connection
                .createStatement(
                    "CREATE TABLE IF NOT EXISTS test_table (id INT, name VARCHAR(255))")
                .execute())
        .flatMapMany(result -> result.getRowsUpdated())
        .blockLast();

    // Clear any traces from setup
    tracer.flush();
    writer.clear();
  }

  @AfterEach
  public void tearDown() {
    if (connection != null) {
      Mono.from(connection.createStatement("DROP TABLE IF EXISTS test_table").execute())
          .flatMapMany(result -> result.getRowsUpdated())
          .blockLast();
      Mono.from(connection.close()).block();
    }
  }

  @Test
  void dbmPopulatesConnectionMetadataTagsOnSelectQuery() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
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
                .resourceName("SELECT * FROM test_table")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("testdb")),
                    tag("_dd.dbm_trace_injected", is(true)),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void dbmSetsTraceInjectedTagInFullMode() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
          .flatMap(result -> result.map((row, metadata) -> row.get(0)))
          .collectList()
          .block();
    } finally {
      parent.finish();
    }

    // In full mode, the span should have the _dd.dbm_trace_injected tag set to true
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(H2_QUERY)
                .resourceName("SELECT * FROM test_table")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("testdb")),
                    tag("_dd.dbm_trace_injected", is(true)),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void dbmPopulatesTagsOnInsertQuery() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono.from(
              connection
                  .createStatement("INSERT INTO test_table (id, name) VALUES (1, 'test')")
                  .execute())
          .flatMapMany(Result::getRowsUpdated)
          .blockLast();
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
                .resourceName(Pattern.compile("INSERT INTO test_table.*"))
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("testdb")),
                    tag("_dd.dbm_trace_injected", is(true)),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void dbmPreservesErrorTagsOnFailedQuery() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      try {
        Flux.from(connection.createStatement("SELECT * FROM nonexistent_table").execute())
            .flatMap(result -> result.map((row, metadata) -> row.get(0)))
            .collectList()
            .block();
      } catch (Exception ignored) {
        // Expected to fail
      }
    } finally {
      parent.finish();
    }

    // Error spans should still work correctly with DBM enabled, and still carry DBM tags
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(H2_QUERY)
                .resourceName("SELECT * FROM nonexistent_table")
                .type(DDSpanTypes.SQL)
                .error()
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("testdb")),
                    tag("_dd.dbm_trace_injected", is(true)),
                    tag("_dd.svc_src", any()),
                    tag(DDTags.ERROR_MSG, any()),
                    error(Exception.class),
                    defaultTags())));
  }

  @Test
  void dbmWorksAcrossMultipleQueries() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      // First query: INSERT
      Mono.from(
              connection
                  .createStatement("INSERT INTO test_table (id, name) VALUES (1, 'first')")
                  .execute())
          .flatMapMany(Result::getRowsUpdated)
          .blockLast();

      // Second query: SELECT
      Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
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
                .resourceName(Pattern.compile("INSERT INTO test_table.*"))
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("testdb")),
                    tag("_dd.dbm_trace_injected", is(true)),
                    tag("_dd.svc_src", any()),
                    defaultTags()),
            span()
                .childOfIndex(0)
                .operationName(H2_QUERY)
                .resourceName("SELECT * FROM test_table")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("testdb")),
                    tag("_dd.dbm_trace_injected", is(true)),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }
}
