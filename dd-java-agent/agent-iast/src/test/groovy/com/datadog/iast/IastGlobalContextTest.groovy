package com.datadog.iast

import com.datadog.iast.model.Range
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class IastGlobalContextTest extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  private RequestContext reqCtx = Stub(RequestContext)

  private AgentSpan span = Stub(AgentSpan) {
    getSpanId() >> 123456
    getRequestContext() >> reqCtx
  }

  protected AgentTracer.TracerAPI tracer = Stub(AgentTracer.TracerAPI) {
    activeSpan() >> span
  }

  private IastGlobalContext.Provider provider

  void setup() {
    AgentTracer.forceRegister(tracer)
    provider = new IastGlobalContext.Provider()
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  void 'provider scopes the context to a request using the global tainted map'() {
    given:
    final iastReqCtx = provider.buildRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> iastReqCtx

    when:
    def resolvedCtx = provider.resolve()

    then:
    resolvedCtx !== iastReqCtx
    resolvedCtx === provider.globalContext
    iastReqCtx.taintedObjects === resolvedCtx.taintedObjects
  }

  void 'release does nothing to the tainted objects'() {
    when:
    final ctx = provider.buildRequestContext()
    final TaintedObjects to = ctx.taintedObjects
    to.taint(UUID.randomUUID(), [] as Range[])

    then:
    to.count() == 1

    when:
    provider.releaseRequestContext(ctx)

    then:
    to.count() == 1
  }
}
