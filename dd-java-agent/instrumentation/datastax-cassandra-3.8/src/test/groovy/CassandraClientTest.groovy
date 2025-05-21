import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

import com.datastax.driver.core.Cluster
import com.datastax.driver.core.Session
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.testcontainers.containers.CassandraContainer
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class CassandraClientTest extends VersionedNamingTestBase {
  private static final int ASYNC_TIMEOUT_MS = 5000

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  Cluster cluster

  @Shared
  int port

  @Shared
  def executor = Executors.newCachedThreadPool()

  @Shared
  CassandraContainer container

  def setupSpec() {
    container = new CassandraContainer("cassandra:3").withStartupTimeout(Duration.ofSeconds(120))
    container.start()
    cluster = container.getCluster()
    port = container.getMappedPort(9042)
    // Looks like sometimes our requests fail because Cassandra takes to long to respond,
    // Increase this timeout as well to try to cope with this.
    cluster.getConfiguration().getSocketOptions().setReadTimeoutMillis(120000)
  }

  def cleanupSpec() {
    container?.stop()
  }

  def "test sync"() {
    setup:

    Session session = keyspace ? cluster.connect(keyspace) : cluster.connect()
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")
    if (extractFromStatement) {
      injectSysConfig(CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED, "true")
    }

    when:
    session.execute(statement)

    then:
    assertTraces(keyspace ? 2 : 1) {
      if (keyspace) {
        trace(1) {
          cassandraSpan(it, "USE $keyspace", null, false)
        }
      }
      trace(1) {
        cassandraSpan(it, statement, expectedKeySpace, renameService)
      }
    }

    cleanup:
    session.close()

    where:
    statement                                                                                         | keyspace    | expectedKeySpace  | renameService | extractFromStatement
    "DROP KEYSPACE IF EXISTS sync_test"                                                               | null        | null              | false         | true
    "DROP KEYSPACE IF EXISTS a_ks"                                                                    | null        | null              | false         | true
    "CREATE KEYSPACE sync_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}" | null        | null              | true          | true
    "CREATE KEYSPACE a_ks WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}"      | null        | null              | false         | true
    "CREATE TABLE sync_test.users ( id UUID PRIMARY KEY, name text )"                                 | "sync_test" | "sync_test"       | false         | true
    "INSERT INTO sync_test.users (id, name) values (uuid(), 'alice')"                                 | "sync_test" | "sync_test"       | false         | true
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                                        | "sync_test" | "sync_test"       | true          | true
    "SELECT * FROM sync_test.users where name = 'alice' ALLOW FILTERING"                              | "a_ks"      | "sync_test"       | false         | true
    "SELECT * FROM sync_test.users where name = 'alice' ALLOW FILTERING"                              | "a_ks"      | "sync_test"       | true          | true
    "SELECT * FROM sync_test.users where name = 'alice' ALLOW FILTERING"                              | null        | "sync_test"       | false         | true
    "SELECT * FROM sync_test.users where name = 'alice' ALLOW FILTERING"                              | null        | "sync_test"       | true          | true
    "SELECT * FROM sync_test.users where name = 'alice' ALLOW FILTERING"                              | null        | null              | false         | false
  }

  def "test async"() {
    setup:

    def callbackExecuted = new CountDownLatch(1)
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")
    if (extractFromStatement) {
      injectSysConfig(CASSANDRA_KEYSPACE_STATEMENT_EXTRACTION_ENABLED, "true")
    }

    when:
    Session session = cluster.connect(keyspace)
    runUnderTrace("parent") {
      def future = session.executeAsync(statement)
      future.addListener({
        ->
        runUnderTrace("callbackListener") {
          callbackExecuted.countDown()
        }
      }, executor)
      blockUntilChildSpansFinished(2)
    }

    then:
    assert callbackExecuted.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    assertTraces(keyspace ? 2 : 1) {
      if (keyspace) {
        trace(1) {
          cassandraSpan(it, "USE $keyspace", null, false)
        }
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        cassandraSpan(it, statement, expectedKeySpace, renameService, span(0))
        basicSpan(it, "callbackListener", span(0))
      }
    }

    cleanup:
    session.close()

    where:
    statement                                                                                          | keyspace     | expectedKeySpace  | renameService | extractFromStatement
    "DROP KEYSPACE IF EXISTS async_test"                                                               | null         | null              | false         | false
    "DROP KEYSPACE IF EXISTS a_ks"                                                                     | null         | null              | false         | false
    "CREATE KEYSPACE async_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}" | null         | null              | true          | false
    "CREATE KEYSPACE a_ks WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}"       | null         | null              | true          | false
    "CREATE TABLE async_test.users ( id UUID PRIMARY KEY, name text )"                                 | "async_test" | "async_test"      | false         | false
    "INSERT INTO async_test.users (id, name) values (uuid(), 'alice')"                                 | "async_test" | "async_test"      | false         | false
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                                         | "async_test" | "async_test"      | false         | false
    "SELECT * FROM async_test.users where name = 'alice' ALLOW FILTERING"                              | null         | null              | false         | false
    "SELECT * FROM async_test.users where name = 'alice' ALLOW FILTERING"                              | "a_ks"       | "a_ks"            | false         | false
    "SELECT * FROM async_test.users where name = 'alice' ALLOW FILTERING"                              | null         | "async_test"      | true          | true
  }

  String normalize(String statement){
    return statement.replaceAll("'alice'", "?")
  }

  def cassandraSpan(TraceAssert trace, String statement, String keyspace, boolean renameService, Object parentSpan = null, Throwable exception = null) {
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
      tags {
        "$Tags.COMPONENT" "java-cassandra"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" container.getHost()
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" "cassandra"
        "$Tags.DB_INSTANCE" keyspace
        "$InstrumentationTags.CASSANDRA_CONTACT_POINTS"  "${container.contactPoint.hostString}:${container.contactPoint.port}"
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
