import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo

import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE

abstract class JDBCDecoratorTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    setupPropagationMode()
  }

  def "Full integration disabled for unsupported DB types (#dbType)"() {
    setup:
    DBInfo dbInfo = new DBInfo.Builder().type(dbType).build()
    expect:
    // check that we get the signal to inject iff both the type and the config say yes
    DECORATE.shouldInjectTraceContext(dbInfo) == (expectedByType && expectedFromConfig())

    where:
    dbType       | expectedByType
    "oracle"     | true
    "sqlserver"  | true
    "mysql"      | true
    "postgresql" | true
  }

  protected abstract void setupPropagationMode()

  protected abstract boolean expectedFromConfig()
}


class JDBCDecoratorFullPropagationForkedTest extends JDBCDecoratorTest {
  @Override
  protected void setupPropagationMode() {
    injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "full")
  }

  @Override
  protected boolean expectedFromConfig() {
    // trace context should be injected only in full mode, this is the only "true".
    return true
  }
}

class JDBCDecoratorServicePropagationForkedTest extends JDBCDecoratorTest {
  @Override
  protected void setupPropagationMode() {
    injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "service")
  }

  @Override
  protected boolean expectedFromConfig() {
    return false
  }
}

class JDBCDecoratorNoPropagationForkedTest extends JDBCDecoratorTest {
  @Override
  protected void setupPropagationMode() {
    injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "disabled")
  }

  @Override
  protected boolean expectedFromConfig() {
    return false
  }
}
