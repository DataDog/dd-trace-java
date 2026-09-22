import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Tests for R2DBC peer service feature. Verifies that the input tags used by {@code
 * PeerServiceCalculator} (peer.hostname, db.instance) are correctly set on database spans when
 * connection options include a HOST. Also verifies that db.user is set when USER is provided.
 *
 * <p>We use H2's in-memory mode with explicit HOST/DATABASE/USER options to exercise the full
 * metadata extraction path. H2 ignores the HOST option for mem connections, but the R2DBC SPI
 * stores all options so our decorator can read them and set the corresponding span tags. The
 * PeerServiceCalculator will compute peer.service from these input tags — we assert on the inputs,
 * not the computed output, per the peer_service feature guide.
 */
class R2dbcPeerServiceTest extends InstrumentationSpecification {

  ConnectionFactory connectionFactory
  Connection connection

  def setup() {
    // Build ConnectionFactoryOptions with explicit HOST, DATABASE, and USER.
    // H2's in-memory protocol ignores HOST but the R2DBC SPI stores all options,
    // so our decorator can read them and set the corresponding span tags.
    connectionFactory = ConnectionFactories.get(
      ConnectionFactoryOptions.builder()
      .option(ConnectionFactoryOptions.DRIVER, "h2")
      .option(ConnectionFactoryOptions.PROTOCOL, "mem")
      .option(ConnectionFactoryOptions.HOST, "db.example.com")
      .option(ConnectionFactoryOptions.DATABASE, "peerdb")
      .option(ConnectionFactoryOptions.USER, "testuser")
      .build())
    connection = Mono.from(connectionFactory.create()).block()

    // Create a test table
    Mono.from(connection.createStatement("CREATE TABLE IF NOT EXISTS peer_test (id INT, name VARCHAR(255))").execute())
      .flatMapMany({ result -> result.getRowsUpdated() })
      .blockLast()

    // Clear any traces from setup
    TEST_WRITER.clear()
  }

  def cleanup() {
    if (connection != null) {
      Mono.from(connection.createStatement("DROP TABLE IF EXISTS peer_test").execute())
        .flatMapMany({ result -> result.getRowsUpdated() })
        .blockLast()
      Mono.from(connection.close()).block()
    }
  }

  def "peer hostname set on select query"() {
    when:
    runUnderTrace("parent") {
      Flux.from(connection.createStatement("SELECT * FROM peer_test").execute())
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
          resourceName "SELECT * FROM peer_test"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" "peerdb"
            "$Tags.PEER_HOSTNAME" "db.example.com"
            "$Tags.DB_USER" "testuser"
            defaultTags()
          }
        }
      }
    }
  }

  def "peer hostname set on insert query"() {
    when:
    runUnderTrace("parent") {
      Mono.from(connection.createStatement("INSERT INTO peer_test (id, name) VALUES (1, 'test')").execute())
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
          resourceName "INSERT INTO peer_test (id, name) VALUES (?, ?)"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" "peerdb"
            "$Tags.PEER_HOSTNAME" "db.example.com"
            "$Tags.DB_USER" "testuser"
            defaultTags()
          }
        }
      }
    }
  }

  def "peer hostname set across multiple queries"() {
    when:
    runUnderTrace("parent") {
      // First query: INSERT
      Mono.from(connection.createStatement("INSERT INTO peer_test (id, name) VALUES (1, 'first')").execute())
        .flatMapMany({ result -> result.getRowsUpdated() })
        .blockLast()

      // Second query: SELECT
      Flux.from(connection.createStatement("SELECT * FROM peer_test").execute())
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
          resourceName "INSERT INTO peer_test (id, name) VALUES (?, ?)"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" "peerdb"
            "$Tags.PEER_HOSTNAME" "db.example.com"
            "$Tags.DB_USER" "testuser"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "h2.query"
          resourceName "SELECT * FROM peer_test"
          spanType DDSpanTypes.SQL
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" "peerdb"
            "$Tags.PEER_HOSTNAME" "db.example.com"
            "$Tags.DB_USER" "testuser"
            defaultTags()
          }
        }
      }
    }
  }

  def "peer hostname preserved on error query"() {
    when:
    runUnderTrace("parent") {
      try {
        Flux.from(connection.createStatement("SELECT * FROM nonexistent_peer_table").execute())
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
    // Even on error, peer.hostname, db.instance, and db.user should still be set
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          childOfPrevious()
          operationName "h2.query"
          resourceName "SELECT * FROM nonexistent_peer_table"
          spanType DDSpanTypes.SQL
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "r2dbc"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "h2"
            "$Tags.DB_INSTANCE" "peerdb"
            "$Tags.PEER_HOSTNAME" "db.example.com"
            "$Tags.DB_USER" "testuser"
            "$DDTags.ERROR_MSG" { it != null }
            errorTags(Exception)
            defaultTags()
          }
        }
      }
    }
  }
}
