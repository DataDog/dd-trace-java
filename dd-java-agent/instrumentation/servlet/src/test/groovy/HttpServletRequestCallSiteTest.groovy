import datadog.smoketest.controller.TestHttpServletRequestCallSiteSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import datadog.trace.instrumentation.servlet.request.HttpServletRequestCallSite

import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

class HttpServletRequestCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getHeader'(final Class<? extends HttpServletRequest> clazz) {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeader('header') >> 'value'
    })

    when:
    final result = testSuite.getHeader('header')

    then:
    result == 'value'
    1 * iastModule.onHeaderValue('header', 'value')

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  def 'test getHeaders'(final Class<? extends HttpServletRequest> clazz) {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeaders('headers') >> Collections.enumeration(['value1', 'value2'])
    })

    when:
    final result = testSuite.getHeaders('headers')?.toList()

    then:
    result == ['value1', 'value2']
    1 * iastModule.onHeaderValue('headers', 'value1')
    1 * iastModule.onHeaderValue('headers', 'value2')

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  def 'test getHeaderNames'(final Class<? extends HttpServletRequest> clazz) {
    setup:
    final iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getHeaderNames() >> Collections.enumeration(['header'])
    })

    when:
    final result = testSuite.getHeaderNames()?.toList()

    then:
    result == ['header']
    1 * iastModule.onHeaderName('header')

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  def 'test getCookies'(final Class<? extends HttpServletRequest> clazz) {
    setup:
    WebModule iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final cookies = [new Cookie('name1', 'value1'), new Cookie('name2', 'value2')]
    final testSuite = new TestHttpServletRequestCallSiteSuite(Mock(clazz) {
      getCookies() >> cookies
    })

    when:
    final result = testSuite.getCookies()

    then:
    result == cookies

    1 * iastModule.onCookies(cookies)

    where:
    clazz                     | _
    HttpServletRequest        | _
    HttpServletRequestWrapper | _
  }

  def 'test that get headers does not fail when servlet related code fails'(final Class<? extends HttpServletRequest> clazz) {
    setup:
    final iastModule = Mock(WebModule)
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
    final iastModule = Mock(WebModule)
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

  private static class NuclearBomb extends RuntimeException {
    NuclearBomb(final String message) {
      super(message)
    }
  }
}
