import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import foo.bar.smoketest.DummyHttpServlet
import foo.bar.smoketest.DummyRequest
import foo.bar.smoketest.DummyResponse
import jakarta.servlet.Servlet
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse

class IastJakartaServletInstrumentationTest extends InstrumentationSpecification{

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test no modules'() {
    final appModule = Mock(ApplicationModule)
    final Servlet servlet = new DummyHttpServlet()
    final ServletResponse response = new DummyResponse()
    final ServletRequest request = new DummyRequest()

    when:
    servlet.callPublicServiceMethod(request, response)

    then:
    0 *  appModule.onRealPath(_)
    0 *  appModule.checkSessionTrackingModes(_)
    0 *  _
  }

  void 'test ApplicationModule'() {
    given:
    final module = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(module)
    final Servlet servlet = new DummyHttpServlet()
    final ServletResponse response = new DummyResponse()
    final ServletRequest request = new DummyRequest()

    when:
    servlet.callPublicServiceMethod(request, response)

    then:
    1 *  module.onRealPath(_)
    0 * _
  }
}
