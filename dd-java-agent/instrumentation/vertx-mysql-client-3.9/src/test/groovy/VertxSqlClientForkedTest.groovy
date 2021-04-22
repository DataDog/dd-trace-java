import TestDatabases.TestDBInfo
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import io.vertx.core.AsyncResult
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.sqlclient.Cursor
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.PreparedStatement
import io.vertx.sqlclient.Query
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.impl.ArrayTuple
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class VertxSqlClientForkedTest extends AgentTestRunner {
  @AutoCleanup
  @Shared
  // This database name must match up with the name in the CircleCI MySQL Docker definition
  def dbs = TestDatabases.initialise("jdbcUnitTest")

  @AutoCleanup
  @Shared
  def vertx = Vertx.vertx(new VertxOptions())

  @Unroll
  def "test #type"() {
    when:
    AsyncResult<RowSet<Row>> asyncResult = runUnderTrace("parent") {
      return executeQueryWithHandler(query)
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0].getInteger(0) == 7
    assertTraces(1) {
      trace(3, true) {
        basicSpan(it, "handler", span(2))
        checkDBSpan(it, span(2), "SELECT ?", "SELECT", dbs.DBInfos.mysql, prepared)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    pool.close()

    where:
    type                 | pool   | query                                         | prepared
    'query'              | pool() | pool.query('SELECT 7')                        | false
    'prepared query'     | pool() | pool.preparedQuery("SELECT 7")                | true
    'prepared statement' | pool() | prepare(connection(pool), "SELECT 7").query() | true
  }

  @Unroll
  def "test #type without parent"() {
    when:
    AsyncResult<RowSet<Row>> asyncResult = executeQueryWithHandler(query)

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0].getInteger(0) == 7
    assertTraces(2) {
      trace(1) {
        checkDBSpan(it, null, "SELECT ?", "SELECT", dbs.DBInfos.mysql, prepared)
      }
      trace(1) {
        basicSpan(it, "handler")
      }
    }

    cleanup:
    pool.close()

    where:
    type                 | pool   | query                                         | prepared
    'query'              | pool() | pool.query('SELECT 7')                        | false
    'prepared query'     | pool() | pool.preparedQuery("SELECT 7")                | true
    'prepared statement' | pool() | prepare(connection(pool), "SELECT 7").query() | true
  }

  @Unroll
  def "test #type mapped"() {
    setup:
    def mapped = query.mapping({row ->
      return row.getInteger(0)
    })

    when:
    AsyncResult<RowSet<Integer>> asyncResult = runUnderTrace("parent") {
      return executeQueryWithHandler(mapped)
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0] == 7
    assertTraces(1) {
      trace(3, true) {
        basicSpan(it, "handler", span(2))
        checkDBSpan(it, span(2), "SELECT ?", "SELECT", dbs.DBInfos.mysql, prepared)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    pool.close()

    where:
    type                 | pool   | query                                         | prepared
    'query'              | pool() | pool.query('SELECT 7')                        | false
    'prepared query'     | pool() | pool.preparedQuery("SELECT 7")                | true
    'prepared statement' | pool() | prepare(connection(pool), "SELECT 7").query() | true
  }

  @Unroll
  def "test #type mapped without parent"() {
    setup:
    def mapped = query.mapping({row ->
      return row.getInteger(0)
    })

    when:
    AsyncResult<RowSet<Integer>> asyncResult = executeQueryWithHandler(mapped)

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0] == 7
    assertTraces(2) {
      trace(1) {
        checkDBSpan(it, null, "SELECT ?", "SELECT", dbs.DBInfos.mysql, prepared)
      }
      trace(1) {
        basicSpan(it, "handler")
      }
    }

    cleanup:
    pool.close()

    where:
    type                 | pool   | query                                         | prepared
    'query'              | pool() | pool.query('SELECT 7')                        | false
    'prepared query'     | pool() | pool.preparedQuery("SELECT 7")                | true
    'prepared statement' | pool() | prepare(connection(pool), "SELECT 7").query() | true
  }

  def "test cursor"() {
    setup:
    def pool = pool()
    def cursor = prepare(connection(pool), "SELECT 7").cursor()

    when:
    def asyncResult = runUnderTrace("parent") {
      queryCursorWithHandler(cursor)
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0].getInteger(0) == 7
    assertTraces(1) {
      trace(3, true) {
        basicSpan(it, "handler", span(2))
        checkDBSpan(it, span(2), "SELECT ?", "SELECT", dbs.DBInfos.mysql, true)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    pool.close()
  }

  def "test row stream"() {
    setup:
    def pool = pool()
    def connection = connection(pool)
    def statement = prepare(connection, "SELECT 7")
    def result = new AtomicReference<Row>()
    def latch = new CountDownLatch(1)

    when:
    def tx = connection.begin()
    def stream = statement.createStream(0, ArrayTuple.EMPTY)
    stream.endHandler({
      tx.commit()
    })
    runUnderTrace("parent") {
      stream.handler({ row ->
        if (latch.getCount() != 0) {
          runUnderTrace("handler") {
            result.set(row)
            stream.close()
          }
          latch.countDown()
        }
      })
    }
    assert latch.await(10, TimeUnit.SECONDS)

    then:
    result.get().getInteger(0) == 7
    assertTraces(1) {
      trace(3, true) {
        basicSpan(it, "handler", span(2))
        checkDBSpan(it, span(2), "SELECT ?", "SELECT", dbs.DBInfos.mysql, true)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    pool.close()
  }

  Pool pool() {
    def connectOptions = MySQLConnectOptions.fromUri(dbs.DBInfos.mysql.uri)
    def poolOptions = new PoolOptions().setMaxSize(2)
    return MySQLPool.pool(vertx, connectOptions, poolOptions)
  }

  public <T> AsyncResult<RowSet<T>> executeQueryWithHandler(Query<RowSet<T>> query) {
    def latch = new CountDownLatch(1)
    AsyncResult<RowSet<T>> result = null
    query.execute { rowSetAR ->
      runUnderTrace("handler") {
        result = rowSetAR
      }
      latch.countDown()
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
    pool.getConnection({connectionAR ->
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
        "$Tags.COMPONENT" prepared == true ? "vertx-sql-prepared_statement" : "vertx-sql-statement"
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
