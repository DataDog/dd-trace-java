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
  // Oracle URL with SID "BENEDB"
  static oracleUrl = "jdbc:oracle:thin:@localhost:1521:BENEDB"
  // Expected: dddbs should be the SID, not the generic "oracle" type.
  // Note: the URL parser lowercases the full URL before extraction, so the SID is lowercase.
  static serviceInjection = "ddps='my_service_name',dddbs='benedb',ddh='localhost',dddb='benedb'"

  TestConnection createOracleConnection() {
    def connection = new TestConnection(false)
    def metadata = new TestDatabaseMetaData()
    metadata.setURL(oracleUrl)
    connection.setMetaData(metadata)
    return connection
  }
}

class OracleInjectionForkedTest extends OracleInjectionTestBase {

  def "Oracle prepared statement injects instance name in dddbs and dddb"() {
    setup:
    def connection = createOracleConnection()

    when:
    def statement = connection.prepareStatement(query) as TestPreparedStatement
    statement.execute()

    then:
    // dddbs must be the Oracle SID "BENEDB", not the generic type "oracle"
    // dddb must also be present with the SID value
    assert statement.sql == "/*${serviceInjection}*/ ${query}"
  }

  def "Oracle single statement injects instance name in dddbs and dddb"() {
    setup:
    def connection = createOracleConnection()

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeQuery(query)

    then:
    // Oracle uses v$session.action for trace context, so no traceparent in comment
    // dddbs must be the Oracle SID "BENEDB", not the generic type "oracle"
    // dddb must also be present with the SID value
    assert statement.sql == "/*${serviceInjection}*/ ${query}"
  }
}
