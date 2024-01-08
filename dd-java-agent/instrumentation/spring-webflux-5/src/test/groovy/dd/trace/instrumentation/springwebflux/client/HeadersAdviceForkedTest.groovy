package dd.trace.instrumentation.springwebflux.client

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.DummyRequest
import org.springframework.http.server.ServletServerHttpRequest

class HeadersAdviceForkedTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'Headers instrumentation'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final ServletServerHttpRequest request = new ServletServerHttpRequest(new DummyRequest())


    when:
    request.getHeaders()

    then:
    2 * module.taint(_ as Object, SourceTypes.REQUEST_HEADER_VALUE)
    0 * _
  }
}
