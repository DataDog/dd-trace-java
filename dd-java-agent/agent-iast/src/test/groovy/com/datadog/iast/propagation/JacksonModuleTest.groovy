package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.taint.Ranges
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.propagation.JacksonModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat

class JacksonModuleTest extends IastModuleImplTestBase {

  private JacksonModule module

  private List<Object> objectHolder

  def setup() {
    module = registerDependencies(new JacksonModuleImpl())
    objectHolder = []
  }

  void 'onJsonFactoryCreateParser null or empty (#content, #result)'(final String content, final Object result) {
    when:
    module.onJsonFactoryCreateParser(content, result)

    then:
    0 * _

    where:
    content | result
    null    | null
    ''      | null
    ''      | new Object()
    null    | new Object()
    'test'  | null
  }
  void 'onJsonFactoryCreateParser without span (#content, #result)'(final String content, final Object result) {
    when:
    module.onJsonFactoryCreateParser(content, result)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    content | result
    'test' | new Object()
  }
  void 'onJsonFactoryCreateParser (#content, #result)'(final Object content, final Object result, final int mockCalls) {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final taintedObjects = ctx.getTaintedObjects()
    def shouldBeTainted = true
    def input
    if(content instanceof String){
      input = addFromTaintFormat(taintedObjects, content)
      objectHolder.add(input)
      shouldBeTainted = fromTaintFormat(content) != null
    }else {
      input = content
      taintedObjects.taint(input, Ranges.EMPTY)
    }

    when:
    module.onJsonFactoryCreateParser(input, result)

    then:
    mockCalls * tracer.activeSpan() >> span
    mockCalls * span.getRequestContext() >> reqCtx
    mockCalls * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(result)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == result
    } else {
      assert to == null
    }

    where:
    content      | result        | mockCalls
    '123'        | new Object()  | 1
    '==>123<=='  | new Object()  | 1
    new ByteArrayInputStream()  | new Object()  | 1
  }
}
