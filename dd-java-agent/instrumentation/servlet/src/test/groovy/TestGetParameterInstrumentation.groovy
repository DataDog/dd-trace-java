import datadog.smoketest.controller.JavaxHttpServletRequestTestSuite
import datadog.smoketest.controller.JavaxHttpServletRequestWrapperTestSuite
import datadog.smoketest.controller.JavaxServletRequestTestSuite
import datadog.smoketest.controller.JavaxServletRequestWrapperTestSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.source.WebModule
import groovy.transform.CompileDynamic

@CompileDynamic
class TestGetParameterInstrumentation extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'test getParameter'() {
    final webMod = Mock(WebModule)
    final propMod = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(webMod)
    InstrumentationBridge.registerIastModule(propMod)
    final map = [param1: ['value1', 'value2'] as String[]]
    final servletRequest = Mock(clazz) {
      getParameter(_ as String) >> { map.get(it[0]).first() }
      getParameterValues(_ as String) >> { map.get(it[0]) }
      getParameterNames() >> { Collections.enumeration(map.keySet()) }
    }
    testSuite.init(servletRequest)

    when:
    testSuite.getParameter('param1')

    then:
    1 * propMod.taint(SourceTypes.REQUEST_PARAMETER_VALUE, 'param1', 'value1')

    when:
    testSuite.getParameterValues('param1')

    then:
    1 * webMod.onParameterValues('param1', ['value1', 'value2'])

    when:
    testSuite.getParameterNames()

    then:
    1 * webMod.onParameterNames(['param1'])

    where:
    testSuite                                     | clazz
    new JavaxServletRequestTestSuite()            | javax.servlet.ServletRequest
    new JavaxHttpServletRequestTestSuite()        | javax.servlet.http.HttpServletRequest
    new JavaxServletRequestWrapperTestSuite()     | javax.servlet.ServletRequestWrapper
    new JavaxHttpServletRequestWrapperTestSuite() | javax.servlet.http.HttpServletRequestWrapper
  }
}
