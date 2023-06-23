package datadog.trace.instrumentation.javax.xml

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XPathInjectionModule

import javax.xml.xpath.XPathFactory

class XPathInstrumentationTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'compile expression calls module onExpression method'() {
    setup:
    final module = Mock(XPathInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    def xp = XPathFactory.newInstance().newXPath()
    final expression = '/bookstore/book[price>35]/price'

    when:
    xp.compile(expression)

    then:
    1 * module.onExpression(expression)
    0 * _
  }
}
