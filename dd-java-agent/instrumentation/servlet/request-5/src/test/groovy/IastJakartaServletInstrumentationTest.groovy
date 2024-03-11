import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.api.iast.sink.SessionRewritingModule
import foo.bar.smoketest.DummyHttpServlet
import foo.bar.smoketest.DummyRequest
import foo.bar.smoketest.DummyResponse
import jakarta.servlet.Servlet
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse



class IastJakartaServletInstrumentationTest extends AgentTestRunner{

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test no modules'() {
    final appModule = Mock(ApplicationModule)
    final sessionModule = Mock(SessionRewritingModule)
    final Servlet servlet = new DummyHttpServlet()
    final ServletResponse response = new DummyResponse()
    final ServletRequest request = new DummyRequest()

    when:
    servlet.callPublicServiceMethod(request, response)

    then:
    0 *  appModule.onRealPath(_)
    0 *  sessionModule.checkSessionTrackingModes(_)
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

  void 'test SessionRewriting'() {
    given:
    final module = Mock(SessionRewritingModule)
    InstrumentationBridge.registerIastModule(module)
    final Servlet servlet = new DummyHttpServlet()
    final ServletResponse response = new DummyResponse()
    final ServletRequest request = new DummyRequest()

    when:
    servlet.callPublicServiceMethod(request, response)

    then:
    1 *  module.checkSessionTrackingModes(['COOKIE', 'URL'] as Set<String>)
    0 * _
  }

  void 'test all modules'() {
    given:
    final appModule = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(appModule)
    final sessionModule = Mock(SessionRewritingModule)
    InstrumentationBridge.registerIastModule(sessionModule)
    final Servlet servlet = new DummyHttpServlet()
    final ServletResponse response = new DummyResponse()
    final ServletRequest request = new DummyRequest()

    when:
    servlet.callPublicServiceMethod(request, response)

    then:
    1 *  appModule.onRealPath(_)
    1 *  sessionModule.checkSessionTrackingModes(['COOKIE', 'URL'] as Set<String>)
    0 * _
  }
}
