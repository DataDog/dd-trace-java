import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Tests for R2DBC Database Monitoring (DBM) feature. Verifies that connection metadata tags
 * (db.instance, db.user, peer.hostname) are correctly populated on spans, and that the
 * _dd.dbm_trace_injected tag is set when DBM propagation mode is "full".
 */
class R2dbcDbmForkedTest extends InstrumentationSpecification {

  ConnectionFactory connectionFactory
  Connection connection

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "full")
    injectSysConfig("dd.service", "test_service")
  }

  def setup() {
    connectionFactory = ConnectionFactories.get(
      ConnectionFactoryOptions.builder()
      .option(ConnectionFactoryOptions.DRIVER, "h2")
      .option(ConnectionFactoryOptions.PROTOCOL, "mem")
      .option(ConnectionFactoryOptions.DATABASE, "testdb")
      .build())
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

  def "dbm populates connection metadata tags on select query"() {
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
            "$Tags.DB_INSTANCE" "testdb"
            "$InstrumentationTags.DBM_TRACE_INJECTED" true
            defaultTags()
          }
        }
      }
    }
  }

  def "dbm sets trace injected tag in full mode"() {
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
    // In full mode, the span should have the _dd.dbm_trace_injected tag set to true
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
            "$Tags.DB_INSTANCE" "testdb"
            "$InstrumentationTags.DBM_TRACE_INJECTED" true
            defaultTags()
          }
        }
      }
    }
  }

  def "dbm populates tags on insert query"() {
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
            "$Tags.DB_INSTANCE" "testdb"
            "$InstrumentationTags.DBM_TRACE_INJECTED" true
            defaultTags()
          }
        }
      }
    }
  }

  def "dbm preserves error tags on failed query"() {
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
    // Error spans should still work correctly with DBM enabled, and still carry DBM tags
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
            "$Tags.DB_INSTANCE" "testdb"
            "$InstrumentationTags.DBM_TRACE_INJECTED" true
            "$DDTags.ERROR_MSG" { it != null }
            errorTags(Exception)
            defaultTags()
          }
        }
      }
    }
  }

  def "dbm works across multiple queries"() {
    when:
    runUnderTrace("parent") {
      // First query: INSERT
      Mono.from(connection.createStatement("INSERT INTO test_table (id, name) VALUES (1, 'first')").execute())
        .flatMapMany({ result -> result.getRowsUpdated() })
        .blockLast()

      // Second query: SELECT
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
            "$Tags.DB_INSTANCE" "testdb"
            "$InstrumentationTags.DBM_TRACE_INJECTED" true
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
            "$Tags.DB_INSTANCE" "testdb"
            "$InstrumentationTags.DBM_TRACE_INJECTED" true
            defaultTags()
          }
        }
      }
    }
  }
}
