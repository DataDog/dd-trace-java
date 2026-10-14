import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.instrumentation.servlet3.TestServlet3

import javax.servlet.Servlet

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class IastTomcatServlet3ForkedTest extends TomcatServlet3TestSync {

  @Override
  Class<Servlet> servlet() {
    return TestServlet3.GetSession
  }

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
    0 * appModule.checkSessionTrackingModes(_)
    0 * _
  }

  void 'test that iast module is called'() {
    given:
    final appModule = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(appModule)
    def request = request(SUCCESS, "GET", null).build()

    when:
    client.newCall(request).execute()

    then:
    1 *  appModule.onRealPath(_)
    1 *  appModule.checkSessionTrackingModes(_)
    0 * _

    when:
    client.newCall(request).execute()

    then: //Only call once per application context
    0 *  appModule.onRealPath(_)
    0 *  appModule.checkSessionTrackingModes(_)
    0 * _
  }
}
