package dd.trace.instrumentation.springwebflux.client

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import foo.bar.DummyRequest
import org.springframework.http.server.ServletServerHttpRequest

class HeadersAdviceForkedTest extends InstrumentationSpecification {

  private Object iastCtx

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void setup() {
    iastCtx = Stub(IastContext)
  }

  void 'Headers instrumentation'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final ServletServerHttpRequest request = new ServletServerHttpRequest(new DummyRequest())


    when:
    runUnderIastTrace { request.getHeaders() }

    then:
    2 * module.taintObject(iastCtx , _ as Object, SourceTypes.REQUEST_HEADER_VALUE)
    0 * _
  }

  protected <E> E runUnderIastTrace(Closure<E> cl) {
    final ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    final span = TEST_TRACER.startSpan("test", "test-iast-span", ddctx)
    try {
      return AgentTracer.activateSpan(span).withCloseable(cl)
    } finally {
      span.finish()
    }
  }
}
