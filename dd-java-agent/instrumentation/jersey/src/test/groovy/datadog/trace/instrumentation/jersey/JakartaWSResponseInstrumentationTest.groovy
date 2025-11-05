package datadog.trace.instrumentation.jersey

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.HttpResponseHeaderModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response

class JakartaWSResponseInstrumentationTest extends InstrumentationSpecification {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'change location header triggers onHeader callback'() {
    setup:
    final redirectionModule = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(redirectionModule)

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
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Response.ok().cookie(new NewCookie("user-id", "7"))

    then:
    1 * module.onHeader('Set-Cookie', 'user-id=7;Version=1')
    0 * _
  }

  def 'insecure cookies added using Response.cookie'(){
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Response.ok().cookie(new NewCookie("user-id", "7"), new NewCookie("ttt", "1"))

    then:
    1 * module.onHeader('Set-Cookie', 'user-id=7;Version=1')
    1 * module.onHeader('Set-Cookie', 'ttt=1;Version=1')
    0 * _
  }
}
