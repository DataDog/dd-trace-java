import akka.actor.ActorSystem
import com.datastax.driver.core.Cluster
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext$
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration
import spock.lang.Shared

import java.util.concurrent.TimeoutException

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class PhantomInstrumentationTest extends AgentTestRunner {

  @Shared
  Cluster cluster
  @Shared
  int port
  @Shared
  BooksOps booksOps
  @Shared
  TestOps testOps
  @Shared
  ActorSystem testSystem = ActorSystem.apply("phantom-instrumentation-test")

  def setupSpec() {
    /*
     This timeout seems excessive but we've seen tests fail with timeout of 40s.
     TODO: if we continue to see failures we may want to consider using 'real' Cassandra
     started in container like we do for memcached. Note: this will complicate things because
     tests would have to assume they run under shared Cassandra and act accordingly.
      */
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(EmbeddedCassandraServerHelper.CASSANDRA_RNDPORT_YML_FILE, 120000L)

    cluster = EmbeddedCassandraServerHelper.getCluster()

    /*
    Looks like sometimes our requests fail because Cassandra takes to long to respond,
    Increase this timeout as well to try to cope with this.
     */
    cluster.getConfiguration().getSocketOptions().setReadTimeoutMillis(120000)
    cluster.newSession().execute("select * from system.local").one()


    // create the DB
    port = EmbeddedCassandraServerHelper.nativeTransportPort
    BooksDatabase booksDatabase = new EmbeddedBooksDatabase(port)
    new BooksDatabaseUtils(booksDatabase).create()

    booksOps = new BooksOps(booksDatabase)
    testOps = new TestOps(booksOps)

    System.out.println("Started embedded cassandra on " + port)
  }

  def cleanupSpec() {
    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
    testSystem.terminate()
  }

  @Shared
  def insertBook = { book, ec ->
    testOps.insertBookAndWait((Book) book, (ExecutionContextExecutor) ec)}

  @Shared
  ExecutionContext globalEc = ExecutionContext$.MODULE$.global()

//  def "test multi operation for expression"() {
//    setup:
//    Book testBook = Book.apply(id, "Code Complete", "McConnell", "OutOfStock", 0)
//    runUnderTrace("parent") {
//      Await.result(testOps.multiOperationExpression(testBook, generalEc, phantomEc), Duration.create(5, "seconds"))
//    }
//
//
//    expect:
//    assertTraces(1) {
//      trace(0, 4) {
//        basicSpan(it, 0, "parent")
////        phantomSpan(it, 1, cql, null, it.span(0), null)
////        cassandraSpan(it, 2, cql, null, false, it.span(1))
//      }
//    }
//
//    cleanup:
//    shutdownScopes(5000)
//
//    where:
//    id                      | phantomEc                  | generalEc
//    UUID.randomUUID()       | globalEc                   | globalEc
////    UUID.randomUUID()       | testSystem.dispatcher      | testSystem.dispatcher
////    UUID.randomUUID()       | globalEc                   | globalEc
////    UUID.randomUUID()       | testSystem.dispatcher      | globalEc
//
//
//  }


  def "test read book" () {
    setup:


    when:
    runUnderTrace("parent") {
      booksOps.getBook(id)
      blockUntilChildSpansFinished(1)
    }

    then:
    assertTraces(1) {
      trace(0, 3) {
        basicSpan(it, 0, "parent")
        phantomSpan(it, 1, cql, it.span(0), null)
        cassandraSpan(it, 2, cql, it.span(1))
      }
    }

    cleanup:
    shutdownScopes(5000)

    where:
    id                | cql
    UUID.randomUUID() | "SELECT * FROM books.books WHERE id = " + id + " LIMIT 1;"

  }

  def "test insert future"() {
    setup:


    when:
    runUnderTrace("parent") {
      Book book = Book.apply(id, title, author, "", 0)
      cmd(book, ec)
      blockUntilChildSpansFinished(1)
    }

    then:
    assertTraces(1) {
      trace(0, 3) {
        basicSpan(it, 1, "parent")
        phantomSpan(it, 0, cql, it.span(1), null)
        cassandraSpan(it, 2, cql, it.span(0))
      }
    }

    cleanup:
    shutdownScopes(5000)

    where:
    title                  | author    | id                       | cmd             | cql                                                                                                | ec
    "Programming in Scala" | "Odersky" | UUID.randomUUID()        | insertBook      | "UPDATE books.books SET title = '" + title + "', author = '" + author + "' WHERE id = " + id + ";" | globalEc
    "Programming in Scala" | "Odersky" | UUID.randomUUID()        | insertBook      | "UPDATE books.books SET title = '" + title + "', author = '" + author + "' WHERE id = " + id + ";" | testSystem.dispatcher
  }

  def shutdownScopes(Long waitMillis) {
    Long deadline = System.currentTimeMillis() + waitMillis
    while (testTracer.activeSpan() != null) {
      if (System.currentTimeMillis() > deadline) {
        throw new TimeoutException("active trace still open: " + testTracer.activeSpan().toString())
      }
      System.println("active scope: " + testTracer.activeScope())
      testTracer.activeScope().close()
      Thread.sleep(100)
    }
    true
  }

  def phantomSpan(TraceAssert trace, int index, String statement, Object parentSpan = null, Throwable exception = null) {
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
      tags {
        "$Tags.COMPONENT" "scala-phantom"
        "$Tags.DB_TYPE" "cassandra"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        defaultTags()
      }
    }
  }

  def cassandraSpan(TraceAssert trace, int index, String statement, Object parentSpan = null, Throwable exception = null) {
    trace.span(index) {
      serviceName "cassandra"
      operationName "cassandra.query"
      resourceName statement
      spanType DDSpanTypes.CASSANDRA
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
    }
  }

}
