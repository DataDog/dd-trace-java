import datadog.smoketest.controller.TestHttpServletRequestCallSiteSuite
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Platform
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import spock.lang.IgnoreIf

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper

@IgnoreIf({
  !Platform.isJavaVersionAtLeast(8)
})
class HttpServletRequestCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test getHeader'(final HttpServletRequest request) {
    setup:
    IastModule iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(request)

    when:
    final result = testSuite.getHeader('header')

    then:
    result == 'value'
    1 * iastModule.onHeaderValue('header', 'value')

    where:
    request                                | _
    prepareMock(HttpServletRequest)        | _
    prepareMock(HttpServletRequestWrapper) | _
  }

  def 'test getHeaders'(final HttpServletRequest request) {
    setup:
    IastModule iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(request)

    when:
    final result = testSuite.getHeaders('header')?.toList()

    then:
    result == ['value1', 'value2']
    1 * iastModule.onHeaderValue('header', 'value')

    where:
    request                                | _
    prepareMock(HttpServletRequest)        | _
    prepareMock(HttpServletRequestWrapper) | _
  }

  def 'test getHeaderNames'(final HttpServletRequest request) {
    setup:
    IastModule iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final testSuite = new TestHttpServletRequestCallSiteSuite(request)

    when:
    final result = testSuite.getHeaderNames()?.toList()

    then:
    result == ['header']
    1 * iastModule.onHeaderName('header')

    where:
    request                                | _
    prepareMock(HttpServletRequest)        | _
    prepareMock(HttpServletRequestWrapper) | _
  }

  private HttpServletRequest prepareMock(final Class<? extends HttpServletRequest> clazz) {
    return Mock(clazz) { HttpServletRequest it ->
      it.getHeader('header') >> 'value'
      it.getHeaders('headers') >> Collections.enumeration(['value1', 'value2'])
      it.getHeaderNames() >> Collections.enumeration(['header'])
    }
  }
}
