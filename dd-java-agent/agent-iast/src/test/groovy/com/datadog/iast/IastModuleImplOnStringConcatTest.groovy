package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringConcatTest extends IastModuleImplTestBase {

  @Shared
  private List<Object> objectHolder = []

  def setup() {
    objectHolder.clear()
  }

  void 'onStringConcat null or empty (#left, #right)'() {
    given:
    final result = left + right

    when:
    module.onStringConcat(left, right, result)

    then:
    0 * _

    where:
    left | right
    ""   | null
    null | ""
    ""   | ""
  }

  void 'onStringConcat without span (#left, #right)'() {
    given:
    final result = left + right

    when:
    module.onStringConcat(left, right, result)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    left | right
    "1"  | null
    null | "2"
    "3"  | "4"
  }

  void 'onStringConcat (#left, #right)'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final taintedObjects = ctx.getTaintedObjects()
    left = addFromTaintFormat(taintedObjects, left)
    objectHolder.add(left)
    right = addFromTaintFormat(taintedObjects, right)
    objectHolder.add(right)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(result) != null

    when:
    module.onStringConcat(left, right, expected)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(result)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == result
      assert taintFormat(to.get() as String, to.getRanges()) == expected
    } else {
      assert to == null
    }

    where:
    left        | right       | expected
    "123"       | null        | "123null"
    null        | "123"       | "null123"
    "123"       | "456"       | "123456"
    "==>123<==" | null        | "==>123<==null"
    null        | "==>123<==" | "null==>123<=="
    "==>123<==" | "456"       | "==>123<==456"
    "123"       | "==>456<==" | "123==>456<=="
    "==>123<==" | "==>456<==" | "==>123<====>456<=="
  }
}
