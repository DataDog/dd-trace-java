package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.assertions.Matcher;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Instrumentation tests for the R2DBC listener-based integration. Verifies that the tracer creates
 * database spans when queries are executed through R2DBC's reactive connection API.
 */
class R2dbcInstrumentationTest extends AbstractInstrumentationTest {

  private static final Pattern H2_QUERY = Pattern.compile("h2\\.query");

  /**
   * Creates a matcher that compares by {@code toString()} to handle both {@code String} and {@code
   * UTF8BytesString} values in tag comparisons.
   */
  @SuppressWarnings("unchecked")
  static <T> Matcher<T> eqs(String expected) {
    return new Matcher<T>() {
      @Override
      public Optional<T> expected() {
        return Optional.of((T) expected);
      }

      @Override
      public String failureReason() {
        return "Unexpected value";
      }

      @Override
      public boolean test(T t) {
        return t != null && expected.equals(t.toString());
      }
    };
  }

  private ConnectionFactory connectionFactory;
  private Connection connection;

  @BeforeEach
  public void setUp() {
    connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///testdb;DB_CLOSE_DELAY=-1");
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
  void selectQueryCreatesSpan() {
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
                    tag(Tags.DB_INSTANCE, any()),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void insertQueryCreatesSpan() {
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
                    tag(Tags.DB_INSTANCE, any()),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void multipleQueriesCreateMultipleSpans() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono.from(
              connection
                  .createStatement("INSERT INTO test_table (id, name) VALUES (1, 'first')")
                  .execute())
          .flatMapMany(Result::getRowsUpdated)
          .blockLast();

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
                    tag(Tags.DB_INSTANCE, any()),
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
                    tag(Tags.DB_INSTANCE, any()),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void errorQuerySetsErrorTags() {
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
                    tag(Tags.DB_INSTANCE, any()),
                    tag("_dd.svc_src", any()),
                    tag(DDTags.ERROR_MSG, any()),
                    error(Exception.class),
                    defaultTags())));
  }

  @Test
  void cancelledQueryStillFinishesSpan() {
    // Insert some data first so the query has something to stream — use a separate
    // span so the INSERT trace doesn't merge with the test's assertion target.
    AgentSpan setupParent = startSpan("test", "setup");
    try (AgentScope setupScope = activateSpan(setupParent)) {
      Mono.from(
              connection
                  .createStatement("INSERT INTO test_table (id, name) VALUES (1, 'a')")
                  .execute())
          .flatMapMany(Result::getRowsUpdated)
          .blockLast();
    } finally {
      setupParent.finish();
    }
    // Clear setup traces
    tracer.flush();
    writer.clear();

    // Now cancel a query mid-stream using take(1)
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
          .flatMap(result -> result.map((row, metadata) -> row.get(0)))
          .take(1)
          .blockLast();
    } finally {
      parent.finish();
    }

    // The key assertion: even though the reactive stream was cancelled via take(1),
    // the span must still finish — no leaked, never-finished spans.
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
                    tag(Tags.DB_INSTANCE, any()),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void queryWithNoActiveTraceDoesNotCreateOrphanSpans() {
    // Execute a query without any active trace context
    Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
        .flatMap(result -> result.map((row, metadata) -> row.get(0)))
        .collectList()
        .block();

    tracer.flush();

    // The listener should still create a span (it wraps the query regardless),
    // but it should be a root span rather than an orphan child.
    blockUntilTracesMatch(traces -> traces.size() >= 1);
    assertTraces(
        trace(
            span()
                .root()
                .operationName(H2_QUERY)
                .resourceName("SELECT * FROM test_table")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, any()),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }
}
