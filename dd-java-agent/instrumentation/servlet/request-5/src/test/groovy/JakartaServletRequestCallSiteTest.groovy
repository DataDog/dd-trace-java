import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.api.iast.source.WebModule
import foo.bar.smoketest.JakartaHttpServletRequestTestSuite
import foo.bar.smoketest.JakartaHttpServletRequestWrapperTestSuite
import foo.bar.smoketest.JakartaServletRequestTestSuite
import foo.bar.smoketest.JakartaServletRequestWrapperTestSuite
import groovy.transform.CompileDynamic
import jakarta.servlet.ServletInputStream

@CompileDynamic
class JakartaServletRequestCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'test getParameter map'() {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final servletRequest = Mock(clazz)
    testSuite.init(servletRequest)
    final map = [param1: ['value1', 'value2'] as String[]]
    servletRequest.getParameterMap() >> map

    when:
    final returnedMap = testSuite.getParameterMap()

    then:
    returnedMap == map
    1 * iastModule.onParameterValues(map)

    where:
    testSuite                                       | clazz
    new JakartaServletRequestTestSuite()            | jakarta.servlet.ServletRequest
    new JakartaHttpServletRequestTestSuite()        | jakarta.servlet.http.HttpServletRequest
    new JakartaServletRequestWrapperTestSuite()     | jakarta.servlet.ServletRequestWrapper
    new JakartaHttpServletRequestWrapperTestSuite() | jakarta.servlet.http.HttpServletRequestWrapper
  }

  void 'test getParameterValues and getParameterNames'() {
    setup:
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
    testSuite                                       | clazz
    new JakartaServletRequestTestSuite()            | jakarta.servlet.ServletRequest
    new JakartaHttpServletRequestTestSuite()        | jakarta.servlet.http.HttpServletRequest
    new JakartaServletRequestWrapperTestSuite()     | jakarta.servlet.ServletRequestWrapper
    new JakartaHttpServletRequestWrapperTestSuite() | jakarta.servlet.http.HttpServletRequestWrapper
  }

  void 'test getRequestDispatcher'() {
    setup:
    final iastModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final servletRequest = Mock(clazz)
    final path = 'http://dummy.location.com'
    testSuite.init(servletRequest)

    when:
    testSuite.getRequestDispatcher(path)

    then:
    1 * servletRequest.getRequestDispatcher(path)
    1 * iastModule.onRedirect(path)

    where:
    testSuite                                       | clazz
    new JakartaServletRequestTestSuite()            | jakarta.servlet.ServletRequest
    new JakartaHttpServletRequestTestSuite()        | jakarta.servlet.http.HttpServletRequest
    new JakartaServletRequestWrapperTestSuite()     | jakarta.servlet.ServletRequestWrapper
    new JakartaHttpServletRequestWrapperTestSuite() | jakarta.servlet.http.HttpServletRequestWrapper
  }

  void 'test getInputStream'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final stream = Mock(ServletInputStream)
    final servletRequest = Mock(clazz) {
      getInputStream() >> stream
    }
    testSuite.init(servletRequest)

    when:
    testSuite.getInputStream()

    then:
    1 * iastModule.taintObject(SourceTypes.REQUEST_BODY, stream)

    where:
    testSuite                                       | clazz
    new JakartaServletRequestTestSuite()            | jakarta.servlet.ServletRequest
    new JakartaHttpServletRequestTestSuite()        | jakarta.servlet.http.HttpServletRequest
    new JakartaServletRequestWrapperTestSuite()     | jakarta.servlet.ServletRequestWrapper
    new JakartaHttpServletRequestWrapperTestSuite() | jakarta.servlet.http.HttpServletRequestWrapper
  }
}
