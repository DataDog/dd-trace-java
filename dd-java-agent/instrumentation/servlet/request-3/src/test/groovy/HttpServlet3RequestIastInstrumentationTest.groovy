import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule

import javax.servlet.http.HttpServletRequest

class HttpServlet3RequestIastInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  def cleanup() {
    final span = TEST_TRACER.activeSpan()
    if (span != null) {
      span.finish()
    }
    InstrumentationBridge.clearIastModules()
  }

  void startRequest(final Object iastRequestContext) {
    def span = TEST_TRACER.buildSpan("test-request").withRequestContextData(RequestContextSlot.IAST, iastRequestContext).start()
    TEST_TRACER.activateNext(span)
  }


  def 'test getParameterMap -> #value and request context = #hasRequestContext'() {
    given:
    def webModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(webModule)
    final req = new TestHttpServletRequest(delegate: Stub(HttpServletRequest) {
      getParameterMap() >> value
    })

    when:
    final iastRequestContext = new Object()
    if (hasRequestContext) {
      startRequest(iastRequestContext)
    }

    then:
    _ * _

    when:
    final result = req.getParameterMap()

    then:
    result.is(value)
    1 * webModule.onParameterValues(value)
    0 * _

    where:
    value                       | hasRequestContext
    [name: ["value"].toArray()] | false
    null                        | false
    [name: ["value"].toArray()] | true
    null                        | true
  }
}
