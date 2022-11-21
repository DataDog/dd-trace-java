import com.datastax.driver.core.Cluster
import com.datastax.driver.core.Session
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

class CassandraClientTest extends AgentTestRunner {
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

  def setupSpec() {
    /*
     This timeout seems excessive but we've seen tests fail with timeout of 40s.
     TODO: if we continue to see failures we may want to consider using 'real' Cassandra
     started in container like we do for memcached. Note: this will complicate things because
     tests would have to assume they run under shared Cassandra and act accordingly.
     */
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(EmbeddedCassandraServerHelper.CASSANDRA_RNDPORT_YML_FILE, 120000L)

    cluster = EmbeddedCassandraServerHelper.getCluster()
    port = EmbeddedCassandraServerHelper.getNativeTransportPort()
    /*
     Looks like sometimes our requests fail because Cassandra takes to long to respond,
     Increase this timeout as well to try to cope with this.
     */
    cluster.getConfiguration().getSocketOptions().setReadTimeoutMillis(120000)
  }

  def cleanupSpec() {
    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
  }

  def "test sync"() {
    setup:

    Session session = cluster.connect(keyspace)
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

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
        cassandraSpan(it, statement, keyspace, renameService)
      }
    }

    cleanup:
    session.close()

    where:
    statement                                                                                         | keyspace    | renameService
    "DROP KEYSPACE IF EXISTS sync_test"                                                               | null        | false
    "CREATE KEYSPACE sync_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}" | null        | true
    "CREATE TABLE sync_test.users ( id UUID PRIMARY KEY, name text )"                                 | "sync_test" | false
    "INSERT INTO sync_test.users (id, name) values (uuid(), 'alice')"                                 | "sync_test" | false
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                                        | "sync_test" | true
  }

  def "test async"() {
    setup:

    def callbackExecuted = new CountDownLatch(1)
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

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
        cassandraSpan(it, statement, keyspace, renameService, span(0))
        basicSpan(it, "callbackListener", span(0))
      }
    }

    cleanup:
    session.close()

    where:
    statement                                                                                          | keyspace     | renameService
    "DROP KEYSPACE IF EXISTS async_test"                                                               | null         | false
    "CREATE KEYSPACE async_test WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor':3}" | null         | true
    "CREATE TABLE async_test.users ( id UUID PRIMARY KEY, name text )"                                 | "async_test" | false
    "INSERT INTO async_test.users (id, name) values (uuid(), 'alice')"                                 | "async_test" | false
    "SELECT * FROM users where name = 'alice' ALLOW FILTERING"                                         | "async_test" | true
  }

  def cassandraSpan(TraceAssert trace, String statement, String keyspace, boolean renameService, Object parentSpan = null, Throwable exception = null) {
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
      tags {
        "$Tags.COMPONENT" "java-cassandra"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" "cassandra"
        "$Tags.DB_INSTANCE" keyspace
        defaultTags()
      }
    }
  }
}
