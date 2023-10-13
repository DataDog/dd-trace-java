import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.smoketest.HttpServlet3TestSuite
import foo.bar.smoketest.HttpServletWrapper3TestSuite
import foo.bar.smoketest.Servlet3TestSuite

import javax.servlet.ServletRequest
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

class Servlet3TestGetParameterInstrumentation extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'test getParameter'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final map = [param1: ['value1', 'value2'] as String[]]
    final request = Mock(requestClass) {
      getParameterMap() >> map
    }

    when:
    final returnedMap = suite.getParameterMap(request)

    then:
    returnedMap == map
    1 * iastModule.onParameterValues(map)

    where:
    suite                              | requestClass
    new Servlet3TestSuite()            | ServletRequest
    new HttpServlet3TestSuite()        | HttpServletRequest
    new HttpServletWrapper3TestSuite() | HttpServletRequestWrapper
  }
}
