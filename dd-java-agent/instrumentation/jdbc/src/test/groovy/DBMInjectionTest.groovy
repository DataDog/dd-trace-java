import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.config.TracerConfig
import test.TestConnection
import test.TestPreparedStatement
import test.TestStatement

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class DBMInjectionTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "full")
    // to check that we use the remapped service name in dddbs
    injectSysConfig(TracerConfig.SERVICE_MAPPING, "testdb:remapped_testdb")
    injectSysConfig("dd.trace.jdbc.prepared.statement.class.name", "test.TestPreparedStatement")
    injectSysConfig("dd.trace.jdbc.connection.class.name", "test.TestConnection")
  }

  static query = "SELECT 1"
  static serviceInjection = "ddps='worker.org.gradle.process.internal.worker.GradleWorkerMain',dddbs='remapped_testdb'"
  static fullInjection = serviceInjection + ",traceparent='00-00000000000000000000000000000005-0000000000000006-01'"

  def "prepared stmt"() {
    setup:
    def connection = new TestConnection(false)
    String sql

    when:
    runUnderTrace("parent") {
      def statement = connection.prepareStatement(query) as TestPreparedStatement
      statement.execute()
      sql = statement.sql
    }

    then:
    // even in full propagation mode, we cannot inject trace info in prepared statements
    assert sql == "/*${serviceInjection}*/ ${query}"
    assertDBTraces()
  }

  def "single query"() {
    setup:
    def connection = new TestConnection(false)
    String sql

    when:
    runUnderTrace("parent") {
      def statement = connection.createStatement() as TestStatement
      statement.executeQuery(query)
      sql = statement.sql
    }

    then:
    assert sql == "/*${fullInjection}*/ ${query}"
    assertDBTraces()
  }

  def assertDBTraces() {
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName "remapped_testdb"
          spanType DDSpanTypes.SQL
        }
      }
    }
    return true
  }
}
