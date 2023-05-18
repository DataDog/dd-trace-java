package datadog.trace.instrumentation.jersey

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import jakarta.ws.rs.core.Response

class JakartaWSResponseInstrumentationTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'change location header triggers onHeader callback'() {
    setup:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Response.status(Response.Status.TEMPORARY_REDIRECT).header("Location", "https://dummy.location.com/test")

    then:
    1 * module.onHeader('Location', 'https://dummy.location.com/test')
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
}
