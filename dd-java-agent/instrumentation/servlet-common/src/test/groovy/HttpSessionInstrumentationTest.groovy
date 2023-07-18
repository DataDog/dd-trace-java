import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule
import foo.bar.DummyHttpSession

class HttpSessionInstrumentationTest  extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'call http session putValue'() {
    setup:
    final httpSession = new DummyHttpSession()
    final module = Mock(TrustBoundaryViolationModule)
    InstrumentationBridge.registerIastModule(module)


    when:
    httpSession.putValue("A", "B")

    then:
    1 * module.onSessionValue("A", "B")
    0 * _
  }

  void 'call http session setAttribute'() {
    setup:
    final httpSession = new DummyHttpSession()
    final module = Mock(TrustBoundaryViolationModule)
    InstrumentationBridge.registerIastModule(module)


    when:
    httpSession.setAttribute("A", "B")

    then:
    1 * module.onSessionValue("A", "B")
    0 * _
  }
}
