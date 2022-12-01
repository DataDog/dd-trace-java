import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.servererrors.SyntaxError
import com.datastax.oss.driver.api.core.session.Session
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.testcontainers.containers.CassandraContainer
import spock.lang.Shared
import spock.util.concurrent.BlockingVariable

import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

class CassandraClientTest extends AgentTestRunner {
  private static final int TIMEOUT = 30

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  int port

  @Shared
  InetSocketAddress address

  @Shared
  CassandraContainer container

  def setupSpec() {
    container = new CassandraContainer("cassandra:4").withStartupTimeout(Duration.ofSeconds(120))
    container.start()
    port = container.getMappedPort(9042)
    address = new InetSocketAddress("127.0.0.1", port)

    runUnderTrace("setup") {
      Session session = sessionBuilder().build()
      session.execute("DROP KEYSPACE IF EXISTS test_keyspace")
      session.execute("CREATE KEYSPACE test_keyspace WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}")
      session.execute("CREATE TABLE test_keyspace.users ( id UUID PRIMARY KEY, name text )")
    }

    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.start()
  }

  def cleanupSpec() {
    container?.stop()
  }

  def "test sync"() {
    setup:
    Session session = sessionBuilder().withKeyspace((String) keyspace).build()
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

    when:
    session.execute(statement)

    then:
    assertTraces(1) {
      trace(1) {
        cassandraSpan(it, statement, keyspace, renameService)
      }
    }

    cleanup:
    session?.close()

    where:
    statement                                                  | keyspace        | renameService
    "DROP KEYSPACE IF EXISTS does_not_exist"                   | null            | false
    "DROP KEYSPACE IF EXISTS does_not_exist"                   | null            | true
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING" | "test_keyspace" | false
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING" | "test_keyspace" | true
  }

  def "test sync with error"() {
    setup:
    def statement = "ILLEGAL STATEMENT"
    Session session = sessionBuilder().withKeyspace((String) keyspace).build()
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

    when:
    session.execute(statement)

    then:
    SyntaxError e = thrown()
    assertTraces(1) {
      trace(1) {
        cassandraSpan(it, statement, keyspace, renameService, null, e)
      }
    }

    cleanup:
    session?.close()

    where:
    keyspace        | renameService
    null            | false
    null            | true
    "test_keyspace" | false
    "test_keyspace" | true
  }

  def "test async"() {
    setup:
    CqlSession session = sessionBuilder().withKeyspace((String) keyspace).build()
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")
    def callbackExecuted = new CountDownLatch(1)


    when:
    runUnderTrace("parent") {
      def future = session.executeAsync(statement)
      future.whenComplete({ result, throwable ->
        runUnderTrace("callbackListener") {
          callbackExecuted.countDown()
        }
      })
      blockUntilChildSpansFinished(2)
    }

    then:
    callbackExecuted.await(TIMEOUT, TimeUnit.SECONDS)

    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        cassandraSpan(it, statement, keyspace, renameService, span(0))
        basicSpan(it, "callbackListener", span(0))
      }
    }

    cleanup:
    session?.close()

    where:
    statement                                                  | keyspace        | renameService
    "DROP KEYSPACE IF EXISTS does_not_exist"                   | null            | false
    "DROP KEYSPACE IF EXISTS does_not_exist"                   | null            | true
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING" | "test_keyspace" | false
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING" | "test_keyspace" | true
  }

  def "test async with error"() {
    setup:
    def statement = "ILLEGAL STATEMENT"
    CqlSession session = sessionBuilder().withKeyspace((String) keyspace).build()
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")
    def callbackExecuted = new BlockingVariable<Throwable>(TIMEOUT)

    when:
    runUnderTrace("parent") {
      def future = session.executeAsync(statement)
      future.whenComplete({ result, throwable ->
        runUnderTrace("callbackListener") {
          callbackExecuted.set(throwable)
        }
      })
      blockUntilChildSpansFinished(2)
    }

    then:
    SyntaxError e = (callbackExecuted.get() as CompletionException).getCause() as SyntaxError
    e != null

    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        cassandraSpan(it, statement, keyspace, renameService, span(0), e)
        basicSpan(it, "callbackListener", span(0))
      }
    }

    cleanup:
    session?.close()

    where:
    keyspace        | renameService
    null            | false
    null            | true
    "test_keyspace" | false
    "test_keyspace" | true
  }

  def sessionBuilder() {
    DriverConfigLoader configLoader = DriverConfigLoader.programmaticBuilder()
      .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(TIMEOUT))
      .build()

    return CqlSession.builder()
      .addContactPoint(address)
      .withLocalDatacenter("datacenter1")
      .withConfigLoader(configLoader)
  }

  def cassandraSpan(TraceAssert trace, String statement, String keyspace, boolean renameService, Object parentSpan = null, Throwable throwable = null) {
    trace.span {
      serviceName renameService && keyspace ? keyspace : "cassandra"
      operationName "cassandra.query"
      resourceName statement
      spanType DDSpanTypes.CASSANDRA
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      errored throwable != null
      tags {
        "$Tags.COMPONENT" "java-cassandra"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" "cassandra"
        "$Tags.DB_INSTANCE" keyspace

        if (throwable != null) {
          errorTags(throwable)
        }
        defaultTags()
      }
    }
  }
}
