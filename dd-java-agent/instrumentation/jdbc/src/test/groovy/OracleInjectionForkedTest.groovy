import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
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
