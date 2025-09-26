import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule
import foo.bar.DummyHttpSession

class HttpSessionInstrumentationTest  extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'test #method'() {
    given:
    final module = Mock(TrustBoundaryViolationModule)
    InstrumentationBridge.registerIastModule(module)
    final args = ['A', 'B']

    when:
    httpSession.&"$method".call(args)

    then:
    expected * module.onSessionValue(args[0], args[1])
    0 * _

    where:
    method         | httpSession                     | expected
    'putValue'     | new DummyHttpSession()          | 1
    'setAttribute' | new DummyHttpSession()          | 1
  }
}
