import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.smoketest.Servlet3TestSuite
import groovy.transform.CompileDynamic

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

@CompileDynamic
class Servlet3TestGetParameterInstrumentation extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test getParameter'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final map = [param1: ['value1', 'value2'] as String[]]
    final servletRequest = Mock(HttpServletRequest) {
      getParameterMap() >> map
    }
    final wrapper = new HttpServletRequestWrapper(servletRequest)
    final testSuite = new Servlet3TestSuite(wrapper)

    when:
    final returnedMap = testSuite.getParameterMap()

    then:
    returnedMap == map
    1 * iastModule.onParameterValues(map)
  }
}
