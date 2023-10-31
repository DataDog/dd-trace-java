package com.datadog.iast


import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IastRequestContextTest extends IastModuleImplTestBase {

  private RequestContext reqCtx

  private IastRequestContext iastCtx

  private AgentSpan span

  void setup() {
    iastCtx = Spy(new IastRequestContext())
    reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> iastCtx
    }
    span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
  }

  void 'get with a span'() {

    when:
    final context = IastRequestContext.get((AgentSpan) null)

    then:
    context == null

    when:
    final context2 = IastRequestContext.get(span)

    then:
    context2 == iastCtx
  }

  void 'get with a request context'() {

    when:
    final context = IastRequestContext.get((RequestContext) null)

    then:
    context == null

    when:
    final context2 = IastRequestContext.get(reqCtx)

    then:
    context2 == iastCtx
  }
}
