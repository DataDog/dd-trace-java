package datadog.trace.instrumentation.cassandra4;

import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNonNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.servererrors.SyntaxError;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.CassandraContainer;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CassandraClientTest extends AbstractInstrumentationTest {

  private static final String OPERATION = "cassandra.query";
  private static final String SERVICE = "cassandra";
  private static final String COMPONENT = "java-cassandra";

  private static CassandraContainer<?> container;
  private static CqlSession session;
  private static int port;
  private static String contactPoint;

  @BeforeAll
  static void setupCassandra() {
    container = new CassandraContainer<>("cassandra:3").withStartupTimeout(Duration.ofSeconds(120));
    container.start();
    port = container.getMappedPort(9042);
    InetSocketAddress contact = container.getContactPoint();
    contactPoint = contact.getHostString() + ":" + contact.getPort();
    session =
        CqlSession.builder()
            .addContactPoint(contact)
            .withLocalDatacenter(container.getLocalDatacenter())
            .build();

    // Wait for connection setup traces and clear them
    try {
      writer.waitForTraces(1);
    } catch (Exception ignored) {
    }
    tracer.flush();
    writer.start();
  }

  @AfterAll
  static void tearDownCassandra() {
    if (session != null) {
      session.close();
    }
    if (container != null) {
      container.stop();
    }
  }

  @Test
  @Order(1)
  void syncCreateKeyspace() {
    session.execute(
        "CREATE KEYSPACE IF NOT EXISTS sync_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}");

    assertTraces(
        trace(
            cassandraSpan(
                "CREATE KEYSPACE IF NOT EXISTS sync_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}",
                null)));
  }

  @Test
  @Order(2)
  void syncCreateTable() {
    session.execute("CREATE TABLE IF NOT EXISTS sync_test.users (id UUID PRIMARY KEY, name text)");

    assertTraces(
        trace(
            cassandraSpan(
                "CREATE TABLE IF NOT EXISTS sync_test.users (id UUID PRIMARY KEY, name text)",
                null)));
  }

  @Test
  @Order(3)
  void syncInsert() {
    session.execute("INSERT INTO sync_test.users (id, name) values (uuid(), 'alice')");

    assertTraces(
        trace(cassandraSpan("INSERT INTO sync_test.users (id, name) values (uuid(), ?)", null)));
  }

  @Test
  @Order(4)
  void syncSelect() {
    ResultSet result =
        session.execute("SELECT * FROM sync_test.users where name = 'alice' ALLOW FILTERING");
    assertNotNull(result);

    assertTraces(
        trace(cassandraSpan("SELECT * FROM sync_test.users where name = ? ALLOW FILTERING", null)));
  }

  @Test
  @Order(5)
  void asyncQuery() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CompletionStage<AsyncResultSet> future =
        session.executeAsync("SELECT * FROM sync_test.users where name = 'alice' ALLOW FILTERING");
    future.whenComplete(
        (result, error) -> {
          latch.countDown();
        });

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Async query did not complete in time");

    assertTraces(
        trace(cassandraSpan("SELECT * FROM sync_test.users where name = ? ALLOW FILTERING", null)));
  }

  @Test
  @Order(6)
  void syncQueryError() {
    assertThrows(
        SyntaxError.class,
        () -> {
          session.execute("INVALID CQL QUERY");
        });

    assertTraces(trace(cassandraSpanWithError("INVALID CQL QUERY", null, SyntaxError.class)));
  }

  @Test
  @Order(7)
  void asyncQueryError() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CompletionStage<AsyncResultSet> future = session.executeAsync("INVALID ASYNC CQL");
    future.whenComplete(
        (result, error) -> {
          latch.countDown();
        });

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Async query did not complete in time");

    assertTraces(trace(cassandraSpanWithError("INVALID ASYNC CQL", null, SyntaxError.class)));
  }

  @Test
  @Order(8)
  void syncBoundStatementExecute() {
    // Prepare does not create a span (not a Statement), only execute does
    PreparedStatement prepared =
        session.prepare("SELECT * FROM sync_test.users where name = ? ALLOW FILTERING");
    BoundStatement bound = prepared.bind("alice");
    ResultSet result = session.execute(bound);
    assertNotNull(result);

    // BoundStatement execution triggers a span with the normalized prepared query
    assertTraces(
        trace(cassandraSpan("SELECT * FROM sync_test.users where name = ? ALLOW FILTERING", null)));
  }

  @Test
  @Order(9)
  void asyncBoundStatementExecute() throws Exception {
    // Prepare does not create a span (not a Statement), only execute does
    PreparedStatement prepared =
        session.prepare("INSERT INTO sync_test.users (id, name) values (uuid(), ?)");
    BoundStatement bound = prepared.bind("bob");
    CountDownLatch latch = new CountDownLatch(1);
    CompletionStage<AsyncResultSet> future = session.executeAsync(bound);
    future.whenComplete(
        (result, error) -> {
          latch.countDown();
        });

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Async prepared query did not complete in time");

    // Async BoundStatement execution triggers a span with the normalized prepared query
    assertTraces(
        trace(cassandraSpan("INSERT INTO sync_test.users (id, name) values (uuid(), ?)", null)));
  }

  @Test
  @Order(10)
  void syncDropKeyspace() {
    session.execute("DROP KEYSPACE IF EXISTS sync_test");

    assertTraces(trace(cassandraSpan("DROP KEYSPACE IF EXISTS sync_test", null)));
  }

  @Test
  @Order(11)
  @WithConfig(key = DB_DBM_PROPAGATION_MODE_MODE, value = "service")
  void dbmServiceModeInjectsSyncQuery() {
    session.execute(
        "CREATE KEYSPACE IF NOT EXISTS dbm_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}");

    assertTraces(
        trace(
            cassandraSpanWithDbm(
                "CREATE KEYSPACE IF NOT EXISTS dbm_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}",
                null)));
  }

  @Test
  @Order(12)
  @WithConfig(key = DB_DBM_PROPAGATION_MODE_MODE, value = "full")
  void dbmFullModeInjectsSyncQuery() {
    session.execute("CREATE TABLE IF NOT EXISTS dbm_test.items (id UUID PRIMARY KEY, name text)");

    assertTraces(
        trace(
            cassandraSpanWithDbm(
                "CREATE TABLE IF NOT EXISTS dbm_test.items (id UUID PRIMARY KEY, name text)",
                null)));
  }

  @Test
  @Order(13)
  @WithConfig(key = DB_DBM_PROPAGATION_MODE_MODE, value = "full")
  void dbmFullModeInjectsAsyncQuery() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CompletionStage<AsyncResultSet> future =
        session.executeAsync("INSERT INTO dbm_test.items (id, name) values (uuid(), 'test_item')");
    future.whenComplete(
        (result, error) -> {
          latch.countDown();
        });

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Async DBM query did not complete in time");

    assertTraces(
        trace(
            cassandraSpanWithDbm(
                "INSERT INTO dbm_test.items (id, name) values (uuid(), ?)", null)));
  }

  @Test
  @Order(14)
  void dbmDisabledDoesNotInject() {
    // With no DBM config, _dd.dbm_trace_injected tag should not be present
    session.execute("SELECT * FROM dbm_test.items LIMIT 1");

    assertTraces(trace(cassandraSpan("SELECT * FROM dbm_test.items LIMIT ?", null)));
  }

  @Test
  @Order(15)
  void dbmCleanup() {
    session.execute("DROP KEYSPACE IF EXISTS dbm_test");

    assertTraces(trace(cassandraSpan("DROP KEYSPACE IF EXISTS dbm_test", null)));
  }

  @Test
  @Order(16)
  void peerServiceInputTagsSetWithoutKeyspace() {
    // Without a keyspace, peer.hostname should still be set (from dbHostname()),
    // but db.instance should not be present.
    session.execute(
        "CREATE KEYSPACE IF NOT EXISTS peer_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}");

    assertTraces(
        trace(
            cassandraSpan(
                "CREATE KEYSPACE IF NOT EXISTS peer_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}",
                null)));
  }

  @Test
  @Order(17)
  void peerServiceInputTagsSetWithKeyspace() {
    // Create a session with a keyspace so db.instance is populated,
    // verifying both peer.hostname and db.instance are set as peer service inputs.
    InetSocketAddress contact = container.getContactPoint();
    try (CqlSession keyspaceSession =
        CqlSession.builder()
            .addContactPoint(contact)
            .withLocalDatacenter(container.getLocalDatacenter())
            .withKeyspace("peer_test")
            .build()) {
      // Clear any setup traces
      try {
        writer.waitForTraces(1);
      } catch (Exception ignored) {
      }
      tracer.flush();
      writer.start();

      keyspaceSession.execute("CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, name text)");

      assertTraces(
          trace(
              cassandraSpanWithKeyspace(
                  "CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, name text)",
                  "\"peer_test\"")));
    }
  }

  @Test
  @Order(18)
  void peerServiceCleanup() {
    session.execute("DROP KEYSPACE IF EXISTS peer_test");

    assertTraces(trace(cassandraSpan("DROP KEYSPACE IF EXISTS peer_test", null)));
  }

  private static SpanMatcher cassandraSpan(String resource, String keyspace) {
    SpanMatcher matcher =
        SpanMatcher.span()
            .root()
            .serviceName(SERVICE)
            .operationName(OPERATION)
            .resourceName(resource)
            .type(DDSpanTypes.CASSANDRA)
            .measured();
    if (keyspace != null) {
      matcher.tags(
          tag(Tags.COMPONENT, is(COMPONENT)),
          tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)),
          tag(Tags.PEER_HOSTNAME, isNonNull()),
          tag(Tags.PEER_PORT, is(port)),
          tag(Tags.DB_TYPE, is("cassandra")),
          tag(Tags.DB_INSTANCE, is(keyspace)),
          tag(Tags.DB_OPERATION, is(resource.split(" ")[0])),
          tag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, is(contactPoint)),
          tag("peer.ipv4", any()),
          tag("_dd.svc_src", any()),
          defaultTags());
    } else {
      matcher.tags(
          tag(Tags.COMPONENT, is(COMPONENT)),
          tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)),
          tag(Tags.PEER_HOSTNAME, isNonNull()),
          tag(Tags.PEER_PORT, is(port)),
          tag(Tags.DB_TYPE, is("cassandra")),
          tag(Tags.DB_OPERATION, is(resource.split(" ")[0])),
          tag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, is(contactPoint)),
          tag("peer.ipv4", any()),
          tag("_dd.svc_src", any()),
          defaultTags());
    }
    return matcher;
  }

  private static SpanMatcher cassandraSpanWithError(
      String resource, String keyspace, Class<? extends Throwable> errorType) {
    SpanMatcher matcher =
        SpanMatcher.span()
            .root()
            .serviceName(SERVICE)
            .operationName(OPERATION)
            .resourceName(resource)
            .type(DDSpanTypes.CASSANDRA)
            .error()
            .measured();
    if (keyspace != null) {
      matcher.tags(
          tag(Tags.COMPONENT, is(COMPONENT)),
          tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)),
          tag(Tags.PEER_HOSTNAME, isNonNull()),
          tag(Tags.DB_TYPE, is("cassandra")),
          tag(Tags.DB_INSTANCE, is(keyspace)),
          tag(Tags.DB_OPERATION, is(resource.split(" ")[0])),
          tag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, is(contactPoint)),
          tag("_dd.svc_src", any()),
          tag("error.message", any()),
          error(errorType),
          defaultTags());
    } else {
      matcher.tags(
          tag(Tags.COMPONENT, is(COMPONENT)),
          tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)),
          tag(Tags.PEER_HOSTNAME, isNonNull()),
          tag(Tags.DB_TYPE, is("cassandra")),
          tag(Tags.DB_OPERATION, is(resource.split(" ")[0])),
          tag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, is(contactPoint)),
          tag("_dd.svc_src", any()),
          tag("error.message", any()),
          error(errorType),
          defaultTags());
    }
    return matcher;
  }

  private static SpanMatcher cassandraSpanWithKeyspace(String resource, String keyspace) {
    SpanMatcher matcher =
        SpanMatcher.span()
            .root()
            .serviceName(SERVICE)
            .operationName(OPERATION)
            .resourceName(resource)
            .type(DDSpanTypes.CASSANDRA)
            .measured();
    matcher.tags(
        tag(Tags.COMPONENT, is(COMPONENT)),
        tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)),
        tag(Tags.PEER_HOSTNAME, isNonNull()),
        tag(Tags.PEER_PORT, is(port)),
        tag(Tags.DB_TYPE, is("cassandra")),
        tag(Tags.DB_INSTANCE, is(keyspace)),
        tag(Tags.DB_OPERATION, is(resource.split(" ")[0])),
        tag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, isNonNull()),
        tag("peer.ipv4", any()),
        tag("_dd.svc_src", any()),
        defaultTags());
    return matcher;
  }

  private static SpanMatcher cassandraSpanWithDbm(String resource, String keyspace) {
    SpanMatcher matcher =
        SpanMatcher.span()
            .root()
            .serviceName(SERVICE)
            .operationName(OPERATION)
            .resourceName(resource)
            .type(DDSpanTypes.CASSANDRA)
            .measured();
    if (keyspace != null) {
      matcher.tags(
          tag(Tags.COMPONENT, is(COMPONENT)),
          tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)),
          tag(Tags.PEER_HOSTNAME, isNonNull()),
          tag(Tags.PEER_PORT, is(port)),
          tag(Tags.DB_TYPE, is("cassandra")),
          tag(Tags.DB_INSTANCE, is(keyspace)),
          tag(Tags.DB_OPERATION, is(resource.split(" ")[0])),
          tag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, is(contactPoint)),
          tag(InstrumentationTags.DBM_TRACE_INJECTED, is(true)),
          tag("peer.ipv4", any()),
          tag("_dd.svc_src", any()),
          defaultTags());
    } else {
      matcher.tags(
          tag(Tags.COMPONENT, is(COMPONENT)),
          tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_CLIENT)),
          tag(Tags.PEER_HOSTNAME, isNonNull()),
          tag(Tags.PEER_PORT, is(port)),
          tag(Tags.DB_TYPE, is("cassandra")),
          tag(Tags.DB_OPERATION, is(resource.split(" ")[0])),
          tag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, is(contactPoint)),
          tag(InstrumentationTags.DBM_TRACE_INJECTED, is(true)),
          tag("peer.ipv4", any()),
          tag("_dd.svc_src", any()),
          defaultTags());
    }
    return matcher;
  }
}
