import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Instrumentation tests for the R2DBC listener-based integration. Verifies that the tracer creates
 * database spans when queries are executed through R2DBC's reactive connection API.
 */
class R2dbcInstrumentationTest extends InstrumentationSpecification {

  ConnectionFactory connectionFactory
  Connection connection

  def setup() {
    connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///testdb;DB_CLOSE_DELAY=-1")
    connection = Mono.from(connectionFactory.create()).block()

    // Create a test table
    Mono.from(connection.createStatement("CREATE TABLE IF NOT EXISTS test_table (id INT, name VARCHAR(255))").execute())
      .flatMapMany({ result -> result.getRowsUpdated() })
      .blockLast()

    // Clear any traces from setup
    TEST_WRITER.clear()
  }

  def cleanup() {
    if (connection != null) {
      Mono.from(connection.createStatement("DROP TABLE IF EXISTS test_table").execute())
        .flatMapMany({ result -> result.getRowsUpdated() })
        .blockLast()
      Mono.from(connection.close()).block()
    }
  }

  def "select query creates span"() {
    when:
    runUnderTrace("parent") {
      Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
        .flatMap({ result ->
          result.map(({
            row, metadata -> row.get(0)
          }) as java.util.function.BiFunction)
        })
        .collectList()
        .block()
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          childOfPrevious()
          operationName "h2.query"
          resourceName "SELECT * FROM test_table"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" { it != null }
            defaultTags()
          }
        }
      }
    }
  }

  def "insert query creates span"() {
    when:
    runUnderTrace("parent") {
      Mono.from(connection.createStatement("INSERT INTO test_table (id, name) VALUES (1, 'test')").execute())
        .flatMapMany({ result -> result.getRowsUpdated() })
        .blockLast()
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          childOfPrevious()
          operationName "h2.query"
          resourceName ~/INSERT INTO test_table.*/
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" { it != null }
            defaultTags()
          }
        }
      }
    }
  }

  def "multiple queries create multiple spans"() {
    when:
    runUnderTrace("parent") {
      Mono.from(connection.createStatement("INSERT INTO test_table (id, name) VALUES (1, 'first')").execute())
        .flatMapMany({ result -> result.getRowsUpdated() })
        .blockLast()

      Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
        .flatMap({ result ->
          result.map(({
            row, metadata -> row.get(0)
          }) as java.util.function.BiFunction)
        })
        .collectList()
        .block()
    }

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          childOf span(0)
          operationName "h2.query"
          resourceName ~/INSERT INTO test_table.*/
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" { it != null }
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "h2.query"
          resourceName "SELECT * FROM test_table"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" { it != null }
            defaultTags()
          }
        }
      }
    }
  }

  def "error query sets error tags"() {
    when:
    runUnderTrace("parent") {
      try {
        Flux.from(connection.createStatement("SELECT * FROM nonexistent_table").execute())
          .flatMap({ result ->
            result.map(({
              row, metadata -> row.get(0)
            }) as java.util.function.BiFunction)
          })
          .collectList()
          .block()
      } catch (Exception ignored) {
        // Expected to fail
      }
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          childOfPrevious()
          operationName "h2.query"
          resourceName "SELECT * FROM nonexistent_table"
          spanType DDSpanTypes.SQL
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" { it != null }
            "$DDTags.ERROR_MSG" { it != null }
            errorTags(Exception)
            defaultTags()
          }
        }
      }
    }
  }

  def "cancelled query still finishes span"() {
    setup:
    // Insert some data first so the query has something to stream — use a separate
    // span so the INSERT trace doesn't merge with the test's assertion target.
    runUnderTrace("setup") {
      Mono.from(connection.createStatement("INSERT INTO test_table (id, name) VALUES (1, 'a')").execute())
        .flatMapMany({ result -> result.getRowsUpdated() })
        .blockLast()
    }
    // Clear setup traces
    TEST_WRITER.clear()

    when:
    // Now cancel a query mid-stream using take(1)
    runUnderTrace("parent") {
      Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
        .flatMap({ result ->
          result.map(({
            row, metadata -> row.get(0)
          }) as java.util.function.BiFunction)
        })
        .take(1)
        .blockLast()
    }

    then:
    // The key assertion: even though the reactive stream was cancelled via take(1),
    // the span must still finish — no leaked, never-finished spans.
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          childOfPrevious()
          operationName "h2.query"
          resourceName "SELECT * FROM test_table"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" { it != null }
            defaultTags()
          }
        }
      }
    }
  }

  def "query with no active trace does not create orphan spans"() {
    when:
    // Execute a query without any active trace context
    Flux.from(connection.createStatement("SELECT * FROM test_table").execute())
      .flatMap({ result ->
        result.map(({
          row, metadata -> row.get(0)
        }) as java.util.function.BiFunction)
      })
      .collectList()
      .block()

    then:
    // The listener should still create a span (it wraps the query regardless),
    // but it should be a root span rather than an orphan child.
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "h2.query"
          resourceName "SELECT * FROM test_table"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" { it != null }
            defaultTags()
          }
        }
      }
    }
  }
}
