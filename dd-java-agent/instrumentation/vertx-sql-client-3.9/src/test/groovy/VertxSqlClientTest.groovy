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
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.PreparedStatement
import io.vertx.sqlclient.Query
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class VertxSqlClientTest extends AgentTestRunner {
  @AutoCleanup
  @Shared
  // This database name must match up with the name in the CircleCI MySQL Docker definition
  def dbs = TestDatabases.initialise("jdbcUnitTest")

  @AutoCleanup
  @Shared
  def vertx = Vertx.vertx(new VertxOptions())

  def "test query"() {
    setup:
    def client = pool()

    when:
    AsyncResult<RowSet<Row>> asyncResult = runUnderTrace("parent") {
      return executeQuery(client.query("SELECT 7"))
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0].getInteger(0) == 7
    assertTraces(1) {
      trace(2, true) {
        checkDBSpan(it, span(1), "SELECT ?", "SELECT", dbs.DBInfos.mysql)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    client.close()
  }

  def "test mapped query"() {
    setup:
    def client = pool()

    when:
    def query = client.query("SELECT 7")
    def mapped = query.mapping({row ->
      return row.getInteger(0)
    })
    AsyncResult<RowSet<Integer>> asyncResult = runUnderTrace("parent") {
      return executeQuery(mapped)
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0] == 7
    assertTraces(1) {
      trace(2, true) {
        checkDBSpan(it, span(1), "SELECT ?", "SELECT", dbs.DBInfos.mysql)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    client.close()
  }

  def "test prepared query"() {
    setup:
    def client = pool()

    when:
    AsyncResult<RowSet<Row>> asyncResult = runUnderTrace("parent") {
      return executeQuery(client.preparedQuery("SELECT 7"))
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0].getInteger(0) == 7
    assertTraces(1) {
      trace(2, true) {
        checkDBSpan(it, span(1), "SELECT ?", "SELECT", dbs.DBInfos.mysql, true)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    client.close()
  }

  def "test mapped prepared query"() {
    setup:
    def client = pool()

    when:
    def query = client.preparedQuery("SELECT 7")
    def mapped = query.mapping({row ->
      return row.getInteger(0)
    })
    AsyncResult<RowSet<Integer>> asyncResult = runUnderTrace("parent") {
      return executeQuery(mapped)
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0] == 7
    assertTraces(1) {
      trace(2, true) {
        checkDBSpan(it, span(1), "SELECT ?", "SELECT", dbs.DBInfos.mysql, true)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    client.close()
  }

  def "test prepared statement"() {
    setup:
    def pool = pool()
    def prepared = prepareStatement(pool, "SELECT 7")

    when:
    AsyncResult<RowSet<Row>> asyncResult = runUnderTrace("parent") {
      return executeQuery(prepared.query())
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0].getInteger(0) == 7
    assertTraces(1) {
      trace(2, true) {
        checkDBSpan(it, span(1), "SELECT ?", "SELECT", dbs.DBInfos.mysql, true)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    pool.close()
  }

  def "test mapped prepared statement"() {
    setup:
    def pool = pool()
    def prepared = prepareStatement(pool, "SELECT 7")

    when:
    def query = prepared.query()
    def mapped = query.mapping({row ->
      return row.getInteger(0)
    })
    AsyncResult<RowSet<Integer>> asyncResult = runUnderTrace("parent") {
      return executeQuery(mapped)
    }

    then:
    asyncResult.succeeded()

    when:
    def result = asyncResult.result()

    then:
    result.size() == 1
    result[0] == 7
    assertTraces(1) {
      trace(2, true) {
        checkDBSpan(it, span(1), "SELECT ?", "SELECT", dbs.DBInfos.mysql, true)
        basicSpan(it, "parent")
      }
    }

    cleanup:
    pool.close()
  }

  Pool pool() {
    def connectOptions = MySQLConnectOptions.fromUri(dbs.DBInfos.mysql.uri)
    def poolOptions = new PoolOptions().setMaxSize(5)
    return MySQLPool.pool(vertx, connectOptions, poolOptions)
  }

  public <T> AsyncResult<RowSet<T>> executeQuery(Query<RowSet<T>> query) {
    def latch = new CountDownLatch(1)
    AsyncResult<RowSet<T>> result = null
    query.execute { rowSetAR ->
      result = rowSetAR
      latch.countDown()
    }
    assert latch.await(10, TimeUnit.SECONDS)
    return result
  }

  PreparedStatement prepareStatement(Pool pool, String sql) {
    def latch = new CountDownLatch(1)
    PreparedStatement result = null
    pool.getConnection({connectionAR ->
      connectionAR.result().prepare(sql, { preparedStatementAR ->
        result = preparedStatementAR.result()
        latch.countDown()
      })
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

  // TODO run all the tests on a separate thread as well, i.e. do the actual query on new thread
}
