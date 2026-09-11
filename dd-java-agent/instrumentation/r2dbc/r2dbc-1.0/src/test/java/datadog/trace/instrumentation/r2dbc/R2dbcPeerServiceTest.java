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

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
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
 * Tests for R2DBC peer service feature. Verifies that the input tags used by {@code
 * PeerServiceCalculator} (peer.hostname, db.instance) are correctly set on database spans when
 * connection options include a HOST. Also verifies that db.user is set when USER is provided.
 *
 * <p>We use H2's in-memory mode with explicit HOST/DATABASE/USER options to exercise the full
 * metadata extraction path. H2 ignores the HOST option for mem connections, but the R2DBC SPI
 * stores all options so our decorator can read them and set the corresponding span tags. The
 * PeerServiceCalculator will compute peer.service from these input tags — we assert on the inputs,
 * not the computed output, per the peer_service feature guide.
 */
class R2dbcPeerServiceTest extends AbstractInstrumentationTest {

  private static final Pattern H2_QUERY = Pattern.compile("h2\\.query");

  private ConnectionFactory connectionFactory;
  private Connection connection;

  @BeforeEach
  public void setUp() {
    // Build ConnectionFactoryOptions with explicit HOST, DATABASE, and USER.
    // H2's in-memory protocol ignores HOST but the R2DBC SPI stores all options,
    // so our decorator can read them and set the corresponding span tags.
    connectionFactory =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "h2")
                .option(ConnectionFactoryOptions.PROTOCOL, "mem")
                .option(ConnectionFactoryOptions.HOST, "db.example.com")
                .option(ConnectionFactoryOptions.DATABASE, "peerdb")
                .option(ConnectionFactoryOptions.USER, "testuser")
                .build());
    connection = Mono.from(connectionFactory.create()).block();

    // Create a test table
    Mono.from(
            connection
                .createStatement("CREATE TABLE IF NOT EXISTS peer_test (id INT, name VARCHAR(255))")
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
      Mono.from(connection.createStatement("DROP TABLE IF EXISTS peer_test").execute())
          .flatMapMany(result -> result.getRowsUpdated())
          .blockLast();
      Mono.from(connection.close()).block();
    }
  }

  @Test
  void peerHostnameSetOnSelectQuery() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flux.from(connection.createStatement("SELECT * FROM peer_test").execute())
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
                .resourceName("SELECT * FROM peer_test")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("peerdb")),
                    tag(Tags.PEER_HOSTNAME, eqs("db.example.com")),
                    tag(Tags.DB_USER, eqs("testuser")),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void peerHostnameSetOnInsertQuery() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono.from(
              connection
                  .createStatement("INSERT INTO peer_test (id, name) VALUES (1, 'test')")
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
                .resourceName("INSERT INTO peer_test (id, name) VALUES (1, 'test')")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("peerdb")),
                    tag(Tags.PEER_HOSTNAME, eqs("db.example.com")),
                    tag(Tags.DB_USER, eqs("testuser")),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void peerHostnameSetAcrossMultipleQueries() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      // First query: INSERT
      Mono.from(
              connection
                  .createStatement("INSERT INTO peer_test (id, name) VALUES (1, 'first')")
                  .execute())
          .flatMapMany(Result::getRowsUpdated)
          .blockLast();

      // Second query: SELECT
      Flux.from(connection.createStatement("SELECT * FROM peer_test").execute())
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
                .resourceName("INSERT INTO peer_test (id, name) VALUES (1, 'first')")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("peerdb")),
                    tag(Tags.PEER_HOSTNAME, eqs("db.example.com")),
                    tag(Tags.DB_USER, eqs("testuser")),
                    tag("_dd.svc_src", any()),
                    defaultTags()),
            span()
                .childOfIndex(0)
                .operationName(H2_QUERY)
                .resourceName("SELECT * FROM peer_test")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("peerdb")),
                    tag(Tags.PEER_HOSTNAME, eqs("db.example.com")),
                    tag(Tags.DB_USER, eqs("testuser")),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void peerHostnamePreservedOnErrorQuery() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      try {
        Flux.from(connection.createStatement("SELECT * FROM nonexistent_peer_table").execute())
            .flatMap(result -> result.map((row, metadata) -> row.get(0)))
            .collectList()
            .block();
      } catch (Exception ignored) {
        // Expected to fail
      }
    } finally {
      parent.finish();
    }

    // Even on error, peer.hostname, db.instance, and db.user should still be set
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(H2_QUERY)
                .resourceName("SELECT * FROM nonexistent_peer_table")
                .type(DDSpanTypes.SQL)
                .error()
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_INSTANCE, eqs("peerdb")),
                    tag(Tags.PEER_HOSTNAME, eqs("db.example.com")),
                    tag(Tags.DB_USER, eqs("testuser")),
                    tag("_dd.svc_src", any()),
                    tag(DDTags.ERROR_MSG, any()),
                    error(Exception.class),
                    defaultTags())));
  }
}
