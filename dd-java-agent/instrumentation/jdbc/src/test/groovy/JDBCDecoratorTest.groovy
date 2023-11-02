import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo

import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE

class JDBCDecoratorTest extends AgentTestRunner {
  def "Full integration disabled for unsupported DB types (#dbType)"() {
    setup:
    injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "full")
    DBInfo dbInfo = new DBInfo.Builder().type(dbType).build()
    expect:
    DECORATE.shouldInjectTraceContext(dbInfo) == expected

    where:
    dbType       | expected
    "oracle"     | false
    "sqlserver"  | false
    "mysql"      | true
    "postgresql" | true
  }
}
