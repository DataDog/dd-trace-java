import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.BaseHash
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.ProcessTags
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import test.TestConnection
import test.TestDatabaseMetaData
import test.TestPreparedStatement
import test.TestStatement

/**
 * Tests that Oracle DBM SQL comment injection produces the correct dddbs and dddb tags.
 *
 * Bug 1: dddbs was populated with the generic type string "oracle" instead of the SID/service name.
 * Bug 2: dddb was never injected because the Oracle URL parser sets instance, not db.
 */
abstract class OracleInjectionTestBase extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "full")
    injectSysConfig("service.name", "my_service_name")
  }

  static query = "SELECT 1"

  // Note: the URL parser lowercases the full URL before extraction, so identifiers are lowercase.
  static sidUrl = "jdbc:oracle:thin:@localhost:1521:BENEDB"
  static serviceNameUrl = "jdbc:oracle:thin:@//localhost:1521/MYSERVICE"

  static sidInjection = "ddps='my_service_name',dddbs='benedb',ddh='localhost',dddb='benedb'"
  static serviceNameInjection = "ddps='my_service_name',dddbs='myservice',ddh='localhost',dddb='myservice'"

  TestConnection createOracleConnection(String url) {
    def connection = new TestConnection(false)
    def metadata = new TestDatabaseMetaData()
    metadata.setURL(url)
    connection.setMetaData(metadata)
    return connection
  }
}

class OracleInjectionForkedTest extends OracleInjectionTestBase {

  def "Oracle prepared statement injects instance name in dddbs and dddb"() {
    setup:
    def connection = createOracleConnection(url)

    when:
    def statement = connection.prepareStatement(query) as TestPreparedStatement
    statement.execute()

    then:
    statement.sql == "/*${expected}*/ ${query}"

    where:
    url            | expected
    sidUrl         | sidInjection
    serviceNameUrl | serviceNameInjection
  }

  def "Oracle single statement injects instance name in dddbs and dddb"() {
    setup:
    def connection = createOracleConnection(url)

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeQuery(query)

    then:
    // Oracle uses v$session.action for trace context, so no traceparent in comment
    statement.sql == "/*${expected}*/ ${query}"

    where:
    url            | expected
    sidUrl         | sidInjection
    serviceNameUrl | serviceNameInjection
  }
}

class OracleDynamicServiceActionInjectionForkedTest extends OracleInjectionTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "dynamic_service")
    injectSysConfig(
      TraceInstrumentationConfig.DB_DBM_PROPAGATION_ORACLE_ACTION_ENABLED, "true")
  }

  def setup() {
    ProcessTags.reset()
    BaseHash.updateBaseHash(-6937226773133363462L)
  }

  def "Oracle dynamic service mode propagates the hash in ACTION without changing statement SQL"() {
    setup:
    def connection = createOracleConnection(serviceNameUrl)
    def statement = connection.createStatement() as TestStatement

    when:
    statement.executeQuery(query)
    statement.executeQuery(query)

    then:
    statement.sql == query
    connection.clientInfoName == "OCSID.ACTION"
    connection.clientInfoValue == "_DD_DDSH:-6937226773133363462"
    connection.clientInfoSetCount == 1
    assertTraces(2) {
      trace(1) {
        span {
          spanType DDSpanTypes.SQL
          tags(false) {
            "$Tags.BASE_HASH" "-6937226773133363462"
          }
        }
      }
      trace(1) {
        span {
          spanType DDSpanTypes.SQL
          tags(false) {
            "$Tags.BASE_HASH" "-6937226773133363462"
          }
        }
      }
    }
  }

  def "Oracle dynamic service mode preserves prepared statement SQL"() {
    setup:
    def connection = createOracleConnection(sidUrl)

    when:
    def statement = connection.prepareStatement(query) as TestPreparedStatement
    statement.execute()

    then:
    statement.sql == query
    connection.clientInfoValue == "_DD_DDSH:-6937226773133363462"
    connection.clientInfoSetCount == 1
  }

  def "Oracle dynamic service mode refreshes ACTION only when the hash changes"() {
    setup:
    def connection = createOracleConnection(serviceNameUrl)
    def statement = connection.createStatement() as TestStatement

    when:
    statement.executeQuery(query)
    BaseHash.updateBaseHash(123456789L)
    statement.executeQuery(query)

    then:
    connection.clientInfoValue == "_DD_DDSH:123456789"
    connection.clientInfoSetCount == 2
  }

  def "Oracle dynamic service mode initializes every connection with the same URL"() {
    setup:
    def firstConnection = createOracleConnection(serviceNameUrl)
    def secondConnection = createOracleConnection(serviceNameUrl)

    when:
    firstConnection.createStatement().executeQuery(query)
    secondConnection.createStatement().executeQuery(query)

    then:
    firstConnection.clientInfoSetCount == 1
    secondConnection.clientInfoSetCount == 1
  }
}
