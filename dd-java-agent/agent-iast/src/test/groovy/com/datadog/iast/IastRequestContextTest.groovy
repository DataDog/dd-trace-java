package com.datadog.iast

import datadog.trace.api.iast.taint.Range
import datadog.trace.api.Config
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.taint.TaintedObjects
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class IastRequestContextTest extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  private RequestContext reqCtx = Mock(RequestContext)

  private AgentSpan span = Mock(AgentSpan) {
    getSpanId() >> 123456
    getRequestContext() >> reqCtx
  }

  protected AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI) {
    activeSpan() >> span
  }

  private IastRequestContext.Provider provider

  void setup() {
    AgentTracer.forceRegister(tracer)
    provider = new IastRequestContext.Provider()
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  void 'provider scopes the context to a request'() {
    given:
    final initialCtx = provider.buildRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> initialCtx

    when:
    def to = provider.resolveTaintedObjects()

    then:
    1 * tracer.activeSpan() >> null
    to == null

    when:
    to = provider.resolveTaintedObjects()

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> null
    to == null

    when:
    to = provider.resolveTaintedObjects()

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    to === initialCtx.taintedObjects
  }

  void 'provider uses a pool of tainted objects'() {
    when:
    final ctx = provider.buildRequestContext()
    final TaintedObjects to = ctx.taintedObjects
    to.taint(UUID.randomUUID(), [] as Range[])

    then:
    to.count() == 1
    provider.pool.size() == 0

    when:
    provider.releaseRequestContext(ctx)

    then:
    to.count() == 0
    provider.pool.size() == 1

    when:
    final maxPoolSize = Config.get().getIastMaxConcurrentRequests()
    final list = (1..2 * maxPoolSize).collect {
      provider.buildRequestContext()
    }

    then:
    provider.pool.size() == 0

    when:
    list.each { provider.releaseRequestContext(it) }

    then:
    provider.pool.size() == maxPoolSize
  }

  void 'ensure that the context releases all tainted objects on close'() {
    setup:
    final ctx = provider.buildRequestContext() as IastRequestContext

    when:
    ctx.withCloseable {
      it.taintedObjects.taint(UUID.randomUUID(), [] as Range[])
    }

    then:
    ctx.taintedObjects.count() == 0

    when:
    ctx.withCloseable {
      it.taintedObjects.taint(UUID.randomUUID(), [] as Range[])
      assert it.taintedObjects.count() == 0
    }

    then:
    ctx.taintedObjects.count() == 0
  }
}
