import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule


class IastServletContextInstrumentationTest extends AgentTestRunner{

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test ApplicationModule onRealPath'() {
    given:
    final module = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(module)
    final utils = new RequestDispatcher2Utils()

    when:
    utils.getRealPath("/")

    then:
    1 *  module.onRealPath(_)
    0 * _
  }
}
