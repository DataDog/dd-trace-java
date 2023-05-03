import datadog.smoketest.controller.ServletRequestTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import groovy.transform.CompileDynamic

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

@CompileDynamic
class TestGetParameterInstrumentation extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test getParameter'() {
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final map = [param1: ['value1', 'value2'] as String[]]
    final servletRequest = Mock(HttpServletRequest) {
      getParameter(_ as String) >> { map.get(it[0]).first() }
      getParameterValues(_ as String) >> { map.get(it[0]) }
      getParameterNames() >> { Collections.enumeration(map.keySet()) }
    }
    final wrapper = new HttpServletRequestWrapper(servletRequest)
    final testSuite = new ServletRequestTestSuite(wrapper)

    when:
    testSuite.getParameter('param1')

    then:
    1 * iastModule.onParameterValue('param1', 'value1')

    when:
    testSuite.getParameterValues('param1')

    then:
    1 * iastModule.onParameterValues('param1', ['value1', 'value2'])

    when:
    testSuite.getParameterNames()

    then:
    1 * iastModule.onParameterNames(['param1'])
  }
}
