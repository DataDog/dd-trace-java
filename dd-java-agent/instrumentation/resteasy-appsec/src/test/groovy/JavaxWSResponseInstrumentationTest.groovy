import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.HttpResponseHeaderModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule

import javax.ws.rs.core.Response

class JavaxWSResponseInstrumentationTest extends InstrumentationSpecification {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'change location header triggers onHeader callback'() {
    setup:
    final module = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Response.status(Response.Status.TEMPORARY_REDIRECT).header("Location", "https://dummy.location.com/test")

    then:
    1 * module.onHeader('Location', 'https://dummy.location.com/test')
  }

  void 'change location triggers onRedirect callback'() {
    setup:
    final headerModule = Mock(HttpResponseHeaderModule)
    InstrumentationBridge.registerIastModule(headerModule)
    final redirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(redirectModule)
    final uri = new URI("https://dummy.location.com/test")

    when:
    Response.status(Response.Status.TEMPORARY_REDIRECT).location(uri)

    then:
    1 * redirectModule.onURIRedirect(uri)
  }
}
