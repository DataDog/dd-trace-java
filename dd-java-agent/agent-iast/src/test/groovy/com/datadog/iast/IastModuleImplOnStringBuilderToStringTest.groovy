package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringBuilderToStringTest extends IastModuleImplTestBase {

  @Shared
  private List<Object> objectHolder = []

  def setup() {
    objectHolder.clear()
  }

  void 'onStringBuilderToString without span'() {
    given:
    final builder = sb('1')
    final result = builder.toString()

    when:
    module.onStringBuilderToString(builder, result)

    then:
    1 * tracer.activeSpan() >> null
    0 * _
  }

  void 'onStringBuilderToString (#originalBuilder)'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final taintedObjects = ctx.getTaintedObjects()
    final builder = addFromTaintFormat(taintedObjects, originalBuilder)
    objectHolder.add(builder)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(result)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    final toString = builder?.toString()
    module.onStringBuilderToString(builder, toString)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(builder)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() as String == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    originalBuilder    | expected
    sb('123')          | '123'
    sb('==>123<==')    | '==>123<=='
    sb('==>123<==456') | '==>123<==456'
  }

  private static StringBuilder sb(final String string) {
    return new StringBuilder(string)
  }
}


