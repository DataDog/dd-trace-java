import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule


class IastServletContextInstrumentationTest extends AgentTestRunner{

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test'() {
    given:
    final module = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(module)
    final dispatcher = new RequestDispatcherUtils()

    when:
    dispatcher.getRealPath("/")

    then:
    1 *  module.onRealPath(_)
    0 * _
  }
}
