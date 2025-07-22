import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TraceInstrumentationConfig
import test.TestConnection
import test.TestDatabaseMetaData
import test.TestStatement

class OracleInjectionForkedTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "full")
    injectSysConfig("service.name", "my_service_name")
  }

  static query = "SELECT 1 FROM DUAL"
  static serviceInjection = "ddps='my_service_name',dddbs='oracle',ddh='localhost',dddb='testdb'"

  def "Oracle no trace injection with full propagation mode"() {
    setup:
    def connection = new TestConnection(false)
    def metadata = new TestDatabaseMetaData()
    metadata.setURL("jdbc:oracle:thin:@localhost:1521:testdb")
    connection.setMetaData(metadata)

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeQuery(query)

    then:
    // Should only have service metadata, not traceparent, because Oracle uses V$SESSION.ACTION
    assert statement.sql == "/*${serviceInjection}*/ ${query}"
    // Verify that the SQL does NOT contain traceparent
    assert !statement.sql.contains("traceparent")
  }
}
