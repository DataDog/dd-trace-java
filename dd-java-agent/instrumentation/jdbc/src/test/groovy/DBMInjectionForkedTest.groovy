import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.config.TracerConfig
import test.TestConnection
import test.TestPreparedStatement
import test.TestStatement

abstract class InjectionTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "full")
    // to check that we use the remapped service name in dddbs
    injectSysConfig("service.name", "my_service_name")
    injectSysConfig(TracerConfig.SERVICE_MAPPING, "testdb:remapped_testdb")
    injectSysConfig("dd.trace.jdbc.prepared.statement.class.name", "test.TestPreparedStatement")
    injectSysConfig("dd.trace.jdbc.connection.class.name", "test.TestConnection")
  }

  static query = "SELECT 1"
  static serviceInjection = "ddps='my_service_name',dddbs='remapped_testdb',ddh='localhost'"
}

class DBMInjectionForkedTest extends InjectionTest {
  def "prepared stmt"() {
    setup:
    def connection = new TestConnection(false)

    when:
    def statement = connection.prepareStatement(query) as TestPreparedStatement
    statement.execute()

    then:
    // even in full propagation mode, we cannot inject trace info in prepared statements
    assert statement.sql == "/*${serviceInjection}*/ ${query}"
  }

  def "single query"() {
    setup:
    def connection = new TestConnection(false)

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeQuery(query)

    then:
    assert statement.sql == "/*${serviceInjection},traceparent='00-00000000000000000000000000000004-0000000000000003-01'*/ ${query}"
  }
}

class DBMAppendInjectionForkedTest extends InjectionTest {
  def "append comment on prepared stmt"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_ALWAYS_APPEND_SQL_COMMENT, "true")
    def connection = new TestConnection(false)

    when:
    def statement = connection.prepareStatement(query) as TestPreparedStatement
    statement.execute()

    then:
    // even in full propagation mode, we cannot inject trace info in prepared statements
    assert statement.sql == "${query} /*${serviceInjection}*/"
  }

  def "append comment on single query"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_ALWAYS_APPEND_SQL_COMMENT, "true")
    def connection = new TestConnection(false)

    when:
    def statement = connection.createStatement() as TestStatement
    statement.executeQuery(query)

    then:
    assert statement.sql == "${query} /*${serviceInjection},traceparent='00-00000000000000000000000000000004-0000000000000003-01'*/"
  }
}
