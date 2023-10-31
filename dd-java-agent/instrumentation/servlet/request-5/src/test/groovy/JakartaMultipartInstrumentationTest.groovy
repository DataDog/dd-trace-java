import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
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
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    part.getName()

    then:
    1 * module.taint('partName', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'Content-Disposition')
    0 * _
  }

  void 'test getHeader'(){
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    part.getHeader('headerName')

    then:
    1 * module.taint('headerValue', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'headerName')
    0 * _
  }

  void 'test getHeaders'(){
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    part.getHeaders('headerName')

    then:
    1 * module.taint('headerValue', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'headerName')
    0 * _
  }

  void 'test getHeaderNames'(){
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    part.getHeaderNames()

    then:
    1 * module.taint('headerName', SourceTypes.REQUEST_MULTIPART_PARAMETER)
    0 * _
  }
}
