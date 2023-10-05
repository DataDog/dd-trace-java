import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.smoketest.MockPart

class JakartaMultipartInstrumentationTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'test getName'() {
    given:
    final module = Mock(WebModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart("partName", "headerValue")

    when:
    part.getName()

    then:
    1 * module.onMultipartValues(_, _)
    0 * _
  }

  void 'test getHeader'(){
    given:
    final module = Mock(WebModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart("partName", "headerValue")

    when:
    part.getHeader("headerName")

    then:
    1 * module.onMultipartValues(_, _)
    0 * _
  }

  void 'test getHeaders'(){
    given:
    final module = Mock(WebModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart("partName", "headerValue")

    when:
    part.getHeaders("headerName")

    then:
    1 *  module.onMultipartValues(_, _)
    0 * _
  }

  void 'test getHeaderNames'(){
    given:
    final module = Mock(WebModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart("partName", "headerValue")

    when:
    part.getHeaderNames()

    then:
    1 * module.onMultipartNames(_)
    0 * _
  }
}
