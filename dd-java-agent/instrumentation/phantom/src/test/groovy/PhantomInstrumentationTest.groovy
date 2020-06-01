import com.datastax.driver.core.Cluster
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import scala.concurrent.ExecutionContextExecutor
import spock.lang.Shared


import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class PhantomInstrumentationTest extends AgentTestRunner {

  @Shared
  Cluster cluster
  @Shared
  int port = 9142
  @Shared
  BooksOps booksOps

  def setupSpec() {
    /*
     This timeout seems excessive but we've seen tests fail with timeout of 40s.
     TODO: if we continue to see failures we may want to consider using 'real' Cassandra
     started in container like we do for memcached. Note: this will complicate things because
     tests would have to assume they run under shared Cassandra and act accordingly.
      */
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(120000L)

    cluster = EmbeddedCassandraServerHelper.getCluster()

    /*
    Looks like sometimes our requests fail because Cassandra takes to long to respond,
    Increase this timeout as well to try to cope with this.
     */
    cluster.getConfiguration().getSocketOptions().setReadTimeoutMillis(120000)
    cluster.newSession().execute("select * from system.local").one()

    // create the DB
    BooksDatabaseUtils$.MODULE$.create()

    booksOps = new BooksOps(EmbeddedBooksDatabase$.MODULE$)

    System.out.println("Started embedded cassandra")
  }

  def cleanupSpec() {
    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
  }

  @Shared
  def insertBook = { id, ec -> booksOps.insertBook((UUID) id, (ExecutionContextExecutor) ec)}
  def "test future"() {
    setup:
    runUnderTrace("parent") {
      cmd(id, ec)
      blockUntilChildSpansFinished(2)
    }

    expect:
    assertTraces(1) {
      trace(0, 3) {
        basicSpan(it, 0, "parent")
        phantomSpan(it, 1, cql, null, it.span(0), null)
        cassandraSpan(it, 2, cql, null, false, it.span(1))
      }
    }

    where:
    id                       | cmd             | cql                                                                                                | ec
    UUID.randomUUID()        | insertBook      | "UPDATE books.books SET title = 'Programming in Scala', author = 'Odersky' WHERE id = " + id + ";" | scala.concurrent.ExecutionContext$.MODULE$.global()
    UUID.randomUUID()        | insertBook      | "UPDATE books.books SET title = 'Programming in Scala', author = 'Odersky' WHERE id = " + id + ";" | com.outworkers.phantom.Manager$.MODULE$.scalaExecutor()
  }

  def phantomSpan(TraceAssert trace, int index, String statement, String keyspace, Object parentSpan = null, Throwable exception = null) {
    trace.span(index) {
      serviceName "phantom"
      operationName "cassandra.query"
      resourceName statement
      spanType "phantom"
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
    }
  }

  def cassandraSpan(TraceAssert trace, int index, String statement, String keyspace, boolean renameService, Object parentSpan = null, Throwable exception = null) {
    trace.span(index) {
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
