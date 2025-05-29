import static datadog.trace.api.config.TraceInstrumentationConfig.CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.servererrors.SyntaxError
import com.datastax.oss.driver.api.core.session.Session
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
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

abstract class CassandraClientTest extends VersionedNamingTestBase {
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
    address = new InetSocketAddress(container.getHost(), port)

    runUnderTrace("setup") {
      Session session = sessionBuilder().build()
      session.execute("DROP KEYSPACE IF EXISTS test_keyspace")
      session.execute("DROP KEYSPACE IF EXISTS a_ks")
      session.execute("CREATE KEYSPACE test_keyspace WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}")
      session.execute("CREATE KEYSPACE a_ks WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}")
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
    if (extractFromStatement) {
      injectSysConfig(CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED, "true")
    }

    when:
    session.execute(statement)

    then:
    assertTraces(1) {
      trace(1) {
        cassandraSpan(it, statement, expectedKeySpace, renameService)
      }
    }

    cleanup:
    session?.close()

    where:

    statement                                                                     | keyspace          | expectedKeySpace  | renameService   | extractFromStatement
    "DROP KEYSPACE IF EXISTS does_not_exist"                                      | null              | null              | false           | true
    "DROP KEYSPACE IF EXISTS does_not_exist"                                      | null              | null              | false           | true
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                    | "test_keyspace"   | "test_keyspace"   | false           | true
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                    | "test_keyspace"   | "test_keyspace"   | true            | true
    "SELECT * FROM test_keyspace.users where name = 'alice' ALLOW FILTERING"      | "a_ks"            | "test_keyspace"   | false           | true
    "SELECT * FROM test_keyspace.users where name = 'alice' ALLOW FILTERING"      | "a_ks"            | "test_keyspace"   | true            | true
    "SELECT * FROM test_keyspace.users where name = 'alice' ALLOW FILTERING"      | null              | "test_keyspace"   | false           | true
    "SELECT * FROM test_keyspace.users where name = 'alice' ALLOW FILTERING"      | null              | "test_keyspace"   | true            | true
    "SELECT * FROM test_keyspace.users where name = 'alice' ALLOW FILTERING"      | null              | null              | false           | false
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
    if (extractFromStatement) {
      injectSysConfig(CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED, "true")
    }
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
        cassandraSpan(it, statement, expectedKeySpace, renameService, span(0))
        basicSpan(it, "callbackListener", span(0))
      }
    }

    cleanup:
    session?.close()

    where:
    statement                                                                     | keyspace          | expectedKeySpace  | renameService   | extractFromStatement
    "DROP KEYSPACE IF EXISTS does_not_exist"                                      | null              | null              | false           | false
    "DROP KEYSPACE IF EXISTS does_not_exist"                                      | null              | null              | false           | false
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                    | "test_keyspace"   | "test_keyspace"   | false           | false
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                    | "test_keyspace"   | "test_keyspace"   | true            | false
    "SELECT * FROM test_keyspace.users where name = 'alice' ALLOW FILTERING"      | null              | null              | false           | false
    "SELECT * FROM test_keyspace.users where name = 'alice' ALLOW FILTERING"      | null              | "test_keyspace"   | true            | true
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

  String normalize(String statement){
    return statement.replaceAll("'alice'", "?")
  }

  def cassandraSpan(TraceAssert trace, String statement, String keyspace, boolean renameService, Object parentSpan = null, Throwable throwable = null) {
    trace.span {
      serviceName renameService && keyspace ? keyspace : service()
      operationName operation()
      resourceName normalize(statement)
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
        "$Tags.PEER_HOSTNAME" container.getHost()
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" "cassandra"
        "$Tags.DB_INSTANCE" keyspace
        "$InstrumentationTags.CASSANDRA_CONTACT_POINTS"  "${container.contactPoint.hostString}:${container.contactPoint.port}"

        if (throwable != null) {
          errorTags(throwable)
        }
        peerServiceFrom(InstrumentationTags.CASSANDRA_CONTACT_POINTS)
        defaultTags()
      }
    }
  }
}

class CassandraClientV0Test extends CassandraClientTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "cassandra"
  }

  @Override
  String operation() {
    return "cassandra.query"
  }
}

class CassandraClientV1ForkedTest extends CassandraClientTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return Config.get().getServiceName()
  }

  @Override
  String operation() {
    return "cassandra.query"
  }
}
