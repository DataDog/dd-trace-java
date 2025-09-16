package datadog.trace.instrumentation.tomcat7

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.StacktraceLeakModule
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ErrorReportValve

class ErrorReportValueInstrumentationForkedTest extends InstrumentationSpecification {

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

class AppSecErrorReportValueInstrumentationForkedTest extends ErrorReportValueInstrumentationForkedTest {

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

class IastErrorReportValueInstrumentationForkedTest extends ErrorReportValueInstrumentationForkedTest {

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

class IastDisabledErrorReportValueInstrumentationForkedTest extends ErrorReportValueInstrumentationForkedTest {

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
