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
    final args = ['A', 'B']

    when:
    httpSession.&"$method".call(args)

    then:
    1 * module.onSessionValue("A", "B")
    0 * _

    where:
    method          | _
    'putValue' | _
    'setAttribute' | _
  }
}
