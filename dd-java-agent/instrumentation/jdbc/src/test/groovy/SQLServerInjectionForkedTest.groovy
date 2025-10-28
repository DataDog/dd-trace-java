

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import test.TestConnection
import test.TestDatabaseMetaData
import test.TestStatement

class SQLServerInjectionForkedTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "full")
    injectSysConfig("service.name", "my_service_name")
  }

  static query = "SELECT 1"
  static serviceInjection = "ddps='my_service_name',dddbs='sqlserver',ddh='localhost',dddb='testdb'"

  def "SQL Server no trace injection with full propagation mode"() {
    setup:
    def connection = new TestConnection(false)
    def metadata = new TestDatabaseMetaData()
    metadata.setURL("jdbc:microsoft:sqlserver://localhost:1433;DatabaseName=testdb;")
    connection.setMetaData(metadata)

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeQuery(query)

    then:
    // Should only have service metadata, not traceparent, because SQL Server uses CONTEXT_INFO
    assert statement.sql == "/*${serviceInjection}*/ ${query}"
    // Verify that the SQL does NOT contain traceparent
    assert !statement.sql.contains("traceparent")
  }

  def "SQL Server apend comment when getting generated keys"() {
    setup:
    def connection = new TestConnection(false)
    def metadata = new TestDatabaseMetaData()
    metadata.setURL("jdbc:microsoft:sqlserver://localhost:1433;DatabaseName=testdb;")
    connection.setMetaData(metadata)

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeUpdate(query, 1)

    then:
    assert statement.sql == "${query} /*${serviceInjection}*/"
  }
}
