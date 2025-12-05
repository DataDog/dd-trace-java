import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_METADATA_FETCHING_ON_CONNECT

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.sql.Statement
import test.TestConnection
import test.TestDatabaseMetaData
import test.TestDriver

abstract class DriverInstrumentationMetadataFetchingTestBase extends InstrumentationSpecification {

  def "test dbInfo extraction from metadata or URL with username"() {
    setup:
    def originalUrl = "jdbc:postgresql://original-host:1234/originaldb"
    def metadataUrl = "jdbc:postgresql://metadata-host:5678/metadatadb"

    def metadata = new TestDatabaseMetaData() {
        @Override
        String getURL() throws SQLException {
          return metadataUrl
        }

        @Override
        String getUserName() throws SQLException {
          return "metadata-user"
        }
      }

    def testConnection = new TestConnection(false)
    testConnection.setMetaData(metadata)

    def driver = new TestDriver() {
        @Override
        Connection connect(String url, Properties info) {
          return testConnection
        }
      }

    def props = new Properties()
    props.put("user", "original-user")

    // Expected values depend on whether metadata fetching is enabled
    def expectedDbInstance = shouldFetchMetadataOnConnect() ? "metadatadb" : "originaldb"
    def expectedDbUser = shouldFetchMetadataOnConnect() ? "metadata-user" : "original-user"

    when:
    def connection = driver.connect(originalUrl, props)
    Statement statement = connection.createStatement()
    runUnderTrace("parent") {
      statement.execute("SELECT 1")
    }

    then:
    assertTraces(1) {
      trace(2) {
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "postgresql.query"
          spanType DDSpanTypes.SQL
          childOf span(0)
          tags(false) {
            "$Tags.DB_INSTANCE" expectedDbInstance
            "$Tags.DB_USER" expectedDbUser
          }
        }
      }
    }

    cleanup:
    statement?.close()
    connection?.close()
  }

  def "test driver connect with null metadata URL"() {
    setup:
    def originalUrl = "jdbc:postgresql://original-host:5432/originaldb"

    def metadata = new TestDatabaseMetaData() {
        @Override
        String getURL() throws SQLException {
          return null
        }
      }

    def testConnection = new TestConnection(false)
    testConnection.setMetaData(metadata)

    def driver = new TestDriver() {
        @Override
        Connection connect(String url, Properties info) {
          return testConnection
        }
      }

    def props = new Properties()

    when:
    def connection = driver.connect(originalUrl, props)
    Statement statement = connection.createStatement()
    runUnderTrace("parent") {
      statement.execute("SELECT 1")
    }

    then:
    assertTraces(1) {
      trace(2) {
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "postgresql.query"
          spanType DDSpanTypes.SQL
          childOf span(0)
          tags(false) {
            // Should fallback to original URL regardless of flag when metadata URL is null
            "$Tags.DB_INSTANCE" "originaldb"
          }
        }
      }
    }

    cleanup:
    statement?.close()
    connection?.close()
  }

  def "test driver connect with metadata exception"() {
    setup:
    def originalUrl = "jdbc:postgresql://original-host:5432/originaldb"

    def testConnection = new TestConnection(false) {
        @Override
        DatabaseMetaData getMetaData() throws SQLException {
          throw new SQLException("Test exception")
        }
      }

    def driver = new TestDriver() {
        @Override
        Connection connect(String url, Properties info) {
          return testConnection
        }
      }

    def props = new Properties()

    when:
    def connection = driver.connect(originalUrl, props)
    Statement statement = connection.createStatement()
    runUnderTrace("parent") {
      statement.execute("SELECT 1")
    }

    then:
    assertTraces(1) {
      trace(2) {
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "postgresql.query"
          spanType DDSpanTypes.SQL
          childOf span(0)
          tags(false) {
            // Should fallback to original URL when exception occurs
            "$Tags.DB_INSTANCE" "originaldb"
          }
        }
      }
    }

    cleanup:
    statement?.close()
    connection?.close()
  }

  def "test driver connect with Oracle sharding driver"() {
    setup:
    def originalUrl = "jdbc:oracle:thin:@original-host:1521:orcl"
    def metadataUrl = "jdbc:oracle:thin:@metadata-host:1521:orcl"

    def metadata = new TestDatabaseMetaData()
    metadata.setURL(metadataUrl)

    def testConnection = new TestConnection(false)
    testConnection.setMetaData(metadata)

    def driver = new TestDriver() {
        @Override
        Connection connect(String url, Properties info) {
          return testConnection
        }
      }

    def props = new Properties()
    props.setProperty("oracle.jdbc.useShardingDriverConnection", "true")

    when:
    def connection = driver.connect(originalUrl, props)
    Statement statement = connection.createStatement()
    runUnderTrace("parent") {
      statement.execute("SELECT 1")
    }

    then:
    assertTraces(1) {
      trace(2) {
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "oracle.query"
          spanType DDSpanTypes.SQL
          childOf span(0)
          tags(false) {
            // Should use original URL for Oracle sharding driver regardless of flag
            "$Tags.DB_INSTANCE" "orcl"
          }
        }
      }
    }

    cleanup:
    statement?.close()
    connection?.close()
  }

  abstract boolean shouldFetchMetadataOnConnect()
}

/**
 * Test with metadata fetching enabled on connect (default behavior)
 */
class DriverInstrumentationWithMetadataOnConnectForkedTest extends DriverInstrumentationMetadataFetchingTestBase {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(DB_METADATA_FETCHING_ON_CONNECT, "true")
  }

  @Override
  boolean shouldFetchMetadataOnConnect() {
    return true
  }
}

/**
 * Test with metadata fetching disabled on connect
 */
class DriverInstrumentationWithoutMetadataOnConnectForkedTest extends DriverInstrumentationMetadataFetchingTestBase {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(DB_METADATA_FETCHING_ON_CONNECT, "false")
  }

  @Override
  boolean shouldFetchMetadataOnConnect() {
    return false
  }
}

