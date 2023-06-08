package datadog.trace.instrumentation.jersey

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.InsecureCookieModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response

class JakartaWSResponseInstrumentationTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'change location header triggers onHeader callback'() {
    setup:
    final redirectionModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectionModule)
    final insecureCookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(insecureCookieModule)

    when:
    Response.status(Response.Status.TEMPORARY_REDIRECT).header("Location", "https://dummy.location.com/test")

    then:
    1 * redirectionModule.onHeader('Location', 'https://dummy.location.com/test')
    0 * _
  }

  void 'change location triggers onRedirect callback'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final uri = new URI("https://dummy.location.com/test")

    when:
    Response.status(Response.Status.TEMPORARY_REDIRECT).location(uri)

    then:
    1 * module.onURIRedirect(uri)
  }

  def 'insecure cookie added using Response.cookie'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Response.ok().cookie(new NewCookie("user-id", "7"))

    then:
    1 * module.onCookies(_)
    0 * _
  }

  def 'insecure cookies added using Response.cookie'(){
    setup:
    final module = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Response.ok().cookie(new NewCookie("user-id", "7"), new NewCookie("ttt", "1"))

    then:
    2 * module.onCookies(_)
  }
}
