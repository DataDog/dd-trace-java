

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TraceInstrumentationConfig
import test.TestConnection
import test.TestDatabaseMetaData
import test.TestStatement

class SQLServerInjectionForkedTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "full")
    injectSysConfig("service.name", "my_service_name")
  }

  static query = "SELECT 1"
  static serviceInjection = "ddps='my_service_name',dddbs='sqlserver',ddh='localhost',dddb='testdb'"

  def "SQL Server no trace injection with full"() {
    setup:
    def connection = new TestConnection(false)
    def metadata = new TestDatabaseMetaData()
    metadata.setURL("jdbc:microsoft:sqlserver://localhost:1433;DatabaseName=testdb;")
    connection.setMetaData(metadata)

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeQuery(query)

    then:
    assert statement.sql == "/*${serviceInjection}*/ ${query}"
  }
}
