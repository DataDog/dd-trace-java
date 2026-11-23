import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class IastJettyServlet2ForkedTest extends JettyServlet2Test implements TestingGenericHttpNamingConventions.ServerV0 {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test no calls if no modules registered'() {
    given:
    final appModule = Mock(ApplicationModule)
    def request = request(SUCCESS, "GET", null).build()

    when:
    client.newCall(request).execute()

    then:
    0 * appModule.onRealPath(_)
    0 * _
  }

  void 'test that iast modules are called'() {
    given:
    final appModule = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(appModule)
    def request = request(SUCCESS, "GET", null).build()

    when:
    client.newCall(request).execute()

    then:
    1 *  appModule.onRealPath(_)
    0 * _

    when:
    client.newCall(request).execute()

    then: //Only call once per application context
    0 *  appModule.onRealPath(_)
    0 * _
  }
}
