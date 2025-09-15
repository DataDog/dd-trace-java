import TestDatabases.TestDBInfo
import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import io.vertx.core.AsyncResult
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.*
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.IgnoreIf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@IgnoreIf(reason = "A change in Locale.ROOT that was introduced in JDK 22 is not fixed until vertx-pg-client v4.5.1: https://github.com/eclipse-vertx/vertx-sql-client/pull/1394", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(22)
})
class VertxPostgresSqlClientForkedTest extends InstrumentationSpecification {
  @AutoCleanup
  @Shared
  def dbs = TestDatabases.initialise("postgres")

  @AutoCleanup
  @Shared
  def vertx = Vertx.vertx(new VertxOptions())

  def "test #type without parent"() {
    when:
    AsyncResult<RowSet<Row>> asyncResult = executeQueryWithHandler(query)

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0].getString(0) == '7'
    assertTraces(2) {
      trace(1) {
        checkDBSpan(it, null, 'SELECT $1', "SELECT", dbs.DBInfos.postgresql, prepared)
      }
      trace(1) {
        basicSpan(it, "handler")
      }
    }

    cleanup:
    pool.close()

    where:
    type                 | pool   | query                                          | prepared
    'prepared statement' | pool() | prepare(connection(pool), 'SELECT $1').query() | true
  }

  Pool pool() {
    def connectOptions = PgConnectOptions.fromUri(dbs.DBInfos.postgresql.uri)
    def poolOptions = new PoolOptions().setMaxSize(2)
    return PgPool.pool(vertx, connectOptions, poolOptions)
  }

  def <T> AsyncResult<RowSet<T>> executeQueryWithHandler(Query<RowSet<T>> query) {
    def latch = new CountDownLatch(1)
    AsyncResult<RowSet<T>> result = null

    if (query instanceof PreparedQuery) {
      query.execute(Tuple.of("7")) { rowSetAR ->
        runUnderTrace("handler") {
          result = rowSetAR
        }
        latch.countDown()
      }
    } else {
      query.execute { rowSetAR ->
        runUnderTrace("handler") {
          result = rowSetAR
        }
        latch.countDown()
      }
    }
    assert latch.await(10, TimeUnit.SECONDS)
    return result
  }

  AsyncResult<RowSet<Row>> queryCursorWithHandler(Cursor cursor) {
    def latch = new CountDownLatch(1)
    AsyncResult<RowSet<Row>> result = null
    cursor.read(0) { rowSetAR ->
      runUnderTrace("handler") {
        result = rowSetAR
      }
      latch.countDown()
    }
    assert latch.await(10, TimeUnit.SECONDS)
    return result
  }

  SqlConnection connection(Pool pool) {
    def latch = new CountDownLatch(1)
    SqlConnection result = null
    pool.getConnection({ connectionAR ->
      result = connectionAR.result()
      latch.countDown()
    })
    assert latch.await(10, TimeUnit.SECONDS)
    return result
  }

  PreparedStatement prepare(SqlConnection connection, String sql) {
    def latch = new CountDownLatch(1)
    PreparedStatement result = null
    connection.prepare(sql, { statementAR ->
      result = statementAR.result()
      latch.countDown()
    })
    assert latch.await(10, TimeUnit.SECONDS)
    return result
  }

  void checkDBSpan(TraceAssert ta, DDSpan parent, String resource, String operation, TestDBInfo info, boolean prepared = false) {
    ta.span(ta.nextSpanId()) {
      if (parent != null) {
        childOf(parent)
      }
      operationName info ? "${info.type}.query" : "database.query"
      resourceName resource
      spanType "sql"
      tags {
        "$Tags.COMPONENT" prepared ? "vertx-sql-prepared_statement" : "vertx-sql-statement"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_OPERATION" operation
        if (info) {
          "$Tags.DB_TYPE" info.type
          "$Tags.DB_INSTANCE" info.dbName
          "$Tags.DB_USER" info.user
          "$Tags.PEER_HOSTNAME" info.host
        }
        defaultTags()
      }
    }
  }
}
