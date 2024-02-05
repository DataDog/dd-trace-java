package datadog.trace.instrumentation.tomcat7

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.StacktraceLeakModule
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ErrorReportValve

class ErrorReportValueInstrumentationTest extends AgentTestRunner {

  void 'test vulnerability detection'() {
    given:
    final reporter = new ErrorReportValve()
    final req = Stub(Request)
    final resp = Stub(Response) {
      getStatus() >> 500
      isError() >> true
    }
    final t = new Throwable()
    final module = Mock(StacktraceLeakModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    reporter.report(req, resp, t)

    then:
    if (shouldReport) {
      1 * module.onStacktraceLeak(t, _, _, _)
    } else {
      0 * module._
    }
  }

  protected boolean isShouldReport() {
    return false
  }
}

class AppSecErrorReportValueInstrumentationTest extends ErrorReportValueInstrumentationTest {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('appsec.enabled', 'true')
    super.configurePreAgent()
  }

  @Override
  protected boolean isShouldReport() {
    return true
  }
}

class IastErrorReportValueInstrumentationTest extends ErrorReportValueInstrumentationTest {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('iast.enabled', 'true')
    super.configurePreAgent()
  }

  @Override
  protected boolean isShouldReport() {
    return true
  }
}

class IastDisabledErrorReportValueInstrumentationTest extends ErrorReportValueInstrumentationTest {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('appsec.enabled', 'true')
    injectSysConfig('iast.enabled', 'false')
    super.configurePreAgent()
  }

  @Override
  protected boolean isShouldReport() {
    return false
  }
}
