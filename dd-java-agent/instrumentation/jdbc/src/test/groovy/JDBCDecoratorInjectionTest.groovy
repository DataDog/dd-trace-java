import static datadog.trace.api.config.TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo

abstract class JDBCDecoratorInjectionTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    setupPropagationMode()
  }

  def "test DBM injection configuration"() {
    setup:
    DBInfo dbInfo = new DBInfo.Builder().type(dbType).build()
    expect:
    // check that we get the signal to inject iff both the type and the config say yes
    DECORATE.shouldInjectTraceContext(dbInfo) == expected()

    where:
    dbType << [
      "oracle",
      "sqlserver",
      "mysql",
      "postgresql",
      "customDB" // injection is enabled by default even if we don't know the DB type
    ]
  }

  protected abstract void setupPropagationMode()

  protected abstract boolean expected()
}


class JDBCDecoratorFullPropagationForkedTest extends JDBCDecoratorInjectionTest {
  @Override
  protected void setupPropagationMode() {
    injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "full")
  }

  @Override
  protected boolean expected() {
    // trace context should be injected only in full mode, this is the only "true".
    return true
  }
}

class JDBCDecoratorServicePropagationForkedTest extends JDBCDecoratorInjectionTest {
  @Override
  protected void setupPropagationMode() {
    injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "service")
  }

  @Override
  protected boolean expected() {
    return false
  }
}

class JDBCDecoratorNoPropagationForkedTest extends JDBCDecoratorInjectionTest {
  @Override
  protected void setupPropagationMode() {
    injectSysConfig(DB_DBM_PROPAGATION_MODE_MODE, "disabled")
  }

  @Override
  protected boolean expected() {
    return false
  }
}
