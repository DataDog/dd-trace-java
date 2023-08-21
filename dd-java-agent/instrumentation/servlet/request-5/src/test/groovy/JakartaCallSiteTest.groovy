import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.smoketest.JakartaTestSuite
import groovy.transform.CompileDynamic
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper

@CompileDynamic
class JakartaCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void  'test getParameter map'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final map = [param1: ['value1', 'value2'] as String[]]
    final servletRequest = Mock(HttpServletRequest)
    servletRequest.getParameterMap() >> map
    final wrapper = new HttpServletRequestWrapper(servletRequest)
    final testSuite = new JakartaTestSuite(wrapper)

    when:
    final returnedMap = testSuite.getParameterMap()

    then:
    returnedMap == map
    1 * iastModule.onParameterValues(map)
  }

  void 'test getParameterValues and getParameterNames'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final map = [param1: ['value1', 'value2'] as String[]]
    final servletRequest = Mock(HttpServletRequest) {
      getParameter(_ as String) >> { map.get(it[0]).first() }
      getParameterValues(_ as String) >> { map.get(it[0]) }
      getParameterNames() >> { Collections.enumeration(map.keySet()) }
    }

    final wrapper = new HttpServletRequestWrapper(servletRequest)
    final JakartaTestSuite testSuite = new JakartaTestSuite(wrapper)

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
