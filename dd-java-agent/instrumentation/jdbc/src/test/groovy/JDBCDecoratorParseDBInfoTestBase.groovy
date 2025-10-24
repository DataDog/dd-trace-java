import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_INFO_FETCHING_ENABLED
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_METADATA_FETCHING_ENABLED

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo
import datadog.trace.instrumentation.jdbc.JDBCDecorator
import java.sql.Connection
import test.TestDatabaseMetaData

/**
 * Base test class for parseDBInfoFromConnection with different flag configurations
 */
abstract class JDBCDecoratorParseDBInfoTestBase extends InstrumentationSpecification {

  def "test parseDBInfoFromConnection with null connection"() {
    when:
    def result = JDBCDecorator.parseDBInfoFromConnection(null)

    then:
    result == DBInfo.DEFAULT
  }

  def "test parseDBInfoFromConnection with null ClientInfo"() {
    setup:
    def metadata = new TestDatabaseMetaData()
    metadata.setURL("jdbc:postgresql://testhost:5432/testdb")
    def connection = Mock(Connection) {
      getMetaData() >> metadata
      getClientInfo() >> null
    }

    when:
    def result = JDBCDecorator.parseDBInfoFromConnection(connection)

    then:
    if (shouldFetchMetadata()) {
      result.type == "postgresql"
      result.host == "testhost"
      result.port == 5432
      result.db == "testdb"
    } else {
      result == DBInfo.DEFAULT
    }
  }

  def "test parseDBInfoFromConnection regular case"() {
    setup:
    def metadata = new TestDatabaseMetaData()
    metadata.setURL("jdbc:postgresql://testhost:5432/testdb")
    def clientInfo = new Properties()
    clientInfo.setProperty("warehouse", "my-test-warehouse") // we'll check that property to know if clientInfo were used
    def connection = Mock(Connection) {
      getMetaData() >> (shouldFetchMetadata() ? metadata : { assert false })
      getClientInfo() >> (shouldFetchClientInfo() ? clientInfo : { assert false })
    }

    when:
    def result = JDBCDecorator.parseDBInfoFromConnection(connection)

    then:
    if (shouldFetchMetadata()) {
      result.type == "postgresql"
      result.host == "testhost"
      result.port == 5432
      result.db == "testdb"
      if (shouldFetchClientInfo()) { // only if we _also_ fetch metadata
        result.warehouse == "my-test-warehouse"
      }
      else {
        result.warehouse == null
      }
    } else {
      result == DBInfo.DEFAULT
    }
  }

  abstract boolean shouldFetchMetadata()

  abstract boolean shouldFetchClientInfo()
}

/**
 * Test with both flags enabled (default behavior)
 */
class JDBCDecoratorParseDBInfoWithMetadataAndClientInfoForkedTest extends JDBCDecoratorParseDBInfoTestBase {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(DB_METADATA_FETCHING_ENABLED, "true")
    injectSysConfig(DB_CLIENT_INFO_FETCHING_ENABLED, "true")
  }

  @Override
  boolean shouldFetchMetadata() {
    return true
  }

  @Override
  boolean shouldFetchClientInfo() {
    return true
  }
}

/**
 * Test with both flags disabled
 */
class JDBCDecoratorParseDBInfoWithoutCallsForkedTest extends JDBCDecoratorParseDBInfoTestBase {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(DB_METADATA_FETCHING_ENABLED, "false")
    injectSysConfig(DB_CLIENT_INFO_FETCHING_ENABLED, "false")
  }

  @Override
  boolean shouldFetchMetadata() {
    return false
  }

  @Override
  boolean shouldFetchClientInfo() {
    return false
  }
}

/**
 * Test with metadata enabled but client info disabled
 */
class JDBCDecoratorParseDBInfoWithMetadataOnlyForkedTest extends JDBCDecoratorParseDBInfoTestBase {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(DB_METADATA_FETCHING_ENABLED, "true")
    injectSysConfig(DB_CLIENT_INFO_FETCHING_ENABLED, "false")
  }

  @Override
  boolean shouldFetchMetadata() {
    return true
  }

  @Override
  boolean shouldFetchClientInfo() {
    return false
  }
}

class JDBCDecoratorParseDBInfoWithClientInfoOnlyForkedTest extends JDBCDecoratorParseDBInfoTestBase {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(DB_METADATA_FETCHING_ENABLED, "false")
    injectSysConfig(DB_CLIENT_INFO_FETCHING_ENABLED, "true")
  }

  @Override
  boolean shouldFetchMetadata() {
    return false
  }

  @Override
  boolean shouldFetchClientInfo() {
    return true
  }
}

