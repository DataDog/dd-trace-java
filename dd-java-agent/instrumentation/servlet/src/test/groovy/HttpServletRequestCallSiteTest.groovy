import datadog.smoketest.controller.TestHttpServletRequestCallSiteSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.instrumentation.servlet.request.HttpServletRequestCallSite
import groovy.transform.CompileDynamic

import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

@CompileDynamic
class HttpServletRequestCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  def cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  def 'test getHeader'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeader('header') >> 'value'
    })

    when:
    final result = testSuite.getHeader('header')

    then:
    result == 'value'
    1 * iastModule.taint('value', SourceTypes.REQUEST_HEADER_VALUE, 'header')

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test getHeaders'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final headers = ['value1', 'value2']
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeaders('headers') >> Collections.enumeration(headers)
    })

    when:
    final result = testSuite.getHeaders('headers')?.toList()

    then:
    result == headers
    headers.each { 1 * iastModule.taint(_, it, SourceTypes.REQUEST_HEADER_VALUE, 'headers') }

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test getHeaderNames'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final headers = ['header1', 'header2']
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeaderNames() >> Collections.enumeration(headers)
    })

    when:
    final result = testSuite.getHeaderNames()?.toList()

    then:
    result == headers
    headers.each { 1 * iastModule.taint(_, it, SourceTypes.REQUEST_HEADER_NAME, it) }

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test getCookies'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookies = [new Cookie('name1', 'value1'), new Cookie('name2', 'value2')] as Cookie[]
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getCookies() >> cookies
    })

    when:
    final result = testSuite.getCookies()

    then:
    result == cookies
    cookies.each {  1 * iastModule.taint(_, it, SourceTypes.REQUEST_COOKIE_VALUE) }

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  def 'test that get headers does not fail when servlet related code fails'(final Class<? extends HttpServletRequest> clazz) {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final enumeration = Mock(Enumeration) {
      hasMoreElements() >> { throw new NuclearBomb('Boom!!!')}
    }
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeaders('header') >> enumeration
    })

    when:
    testSuite.getHeaders('header')

    then:
    final bomb = thrown(NuclearBomb)
    bomb.stackTrace.find { it.className == HttpServletRequestCallSite.name } == null

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  def 'test that get header names does not fail when servlet related code fails'(final Class<? extends HttpServletRequest> clazz) {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final enumeration = Mock(Enumeration) {
      hasMoreElements() >> { throw new NuclearBomb('Boom!!!')}
    }
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeaderNames() >> enumeration
    })

    when:
    testSuite.getHeaderNames()

    then:
    final bomb = thrown(NuclearBomb)
    bomb.stackTrace.find { it.className == HttpServletRequestCallSite.name } == null

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test get query string'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getQueryString() >> 'paramName=paramValue'
    })

    when:
    testSuite.getQueryString()

    then:

    1 * iastModule.taint('paramName=paramValue', SourceTypes.REQUEST_QUERY)

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test getRequestURI'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final mock = Mock(clazz){
      getRequestURI() >> 'retValue'
    }
    final testSuite = new TestHttpServletRequestCallSiteSuite(mock)

    when:
    testSuite.getRequestURI()

    then:
    1 * iastModule.taint('retValue', SourceTypes.REQUEST_PATH)

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test getRequestURL'() {
    setup:
    final module = Mock(PropagationModule)
    final retValue = new StringBuffer("retValue")
    InstrumentationBridge.registerIastModule(module)
    final mock = Mock(clazz){
      getRequestURL() >> retValue
    }
    final testSuite = new TestHttpServletRequestCallSiteSuite(mock)

    when:
    testSuite.getRequestURL()

    then:
    1 * module.taint(retValue, SourceTypes.REQUEST_URI)

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }


  void 'test getPathInfo'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final mock = Mock(HttpServletRequest){
      getPathInfo() >> 'retValue'
    }
    final testSuite = new TestHttpServletRequestCallSiteSuite(mock)

    when:
    testSuite.getPathInfo()

    then:
    1 * iastModule.taint('retValue', SourceTypes.REQUEST_PATH)

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  void 'test getPathTranslated'() {
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final mock = Mock(HttpServletRequest){
      getPathTranslated() >> 'retValue'
    }
    final testSuite = new TestHttpServletRequestCallSiteSuite(mock)

    when:
    testSuite.getPathTranslated()

    then:
    1 * iastModule.taint('retValue', SourceTypes.REQUEST_PATH)

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }


  private static class NuclearBomb extends RuntimeException {
    NuclearBomb(final String message) {
      super(message)
    }
  }
}
