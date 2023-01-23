package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.taint.Ranges
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.propagation.IOModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IOModuleTest extends IastModuleImplTestBase {

  private IOModule module

  private List<Object> objectHolder

  def setup() {
    module = registerDependencies(new IOModuleImpl())
    objectHolder = []
  }

  void 'onConstruct null or empty (#param, #self)'(final InputStream param, final InputStream self) {
    when:
    module.onConstruct(param, self)

    then:
    0 * _

    where:
    param                      | self
    null                       | null
    new ByteArrayInputStream() | null
    null                       | new ByteArrayInputStream()
  }

  void 'onConstruct without span'() {
    given:
    final io = new ByteArrayInputStream()
    final wrapper = new PushbackInputStream(io)

    when:
    module.onConstruct(io, wrapper)

    then:
    1 * tracer.activeSpan() >> null
    0 * _
  }

  void 'onConstruct  (#isParamTainted, #isSelfTainted)'(boolean isParamTainted, boolean isSelfTainted) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final taintedObjects = ctx.getTaintedObjects()
    def shouldBeTainted = isParamTainted && !isSelfTainted
    final param = new ByteArrayInputStream()
    final self = new PushbackInputStream(param)

    if(isParamTainted){
      taintedObjects.taint(param, Ranges.EMPTY)
    }

    if(isSelfTainted){
      taintedObjects.taint(self, Ranges.EMPTY)
    }

    when:
    module.onConstruct(param, self)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _

    def to = ctx.getTaintedObjects().get(self)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == self
    } else {
      assert to == null
    }

    where:
    isParamTainted | isSelfTainted
    false          | false
    true           | false
  }
}
