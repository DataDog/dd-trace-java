package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringBuilderAppendTest extends IastModuleImplTestBase {

  @Shared
  private List<Object> objectHolder = []

  def setup() {
    objectHolder.clear()
  }

  void 'onStringBuilderAppend null or empty (#builder, #param)'() {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderAppend(result, param)

    then:
    0 * _

    where:
    builder | param
    sb('')  | null
    sb('')  | ''
  }

  void 'onStringBuilderAppend without span (#builder, #param)'() {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderAppend(result, param)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    builder | param | mockCalls
    sb('1') | null  | 0
    sb('3') | '4'   | 1
  }

  void 'onStringBuilderAppend (#builder, #param)'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final taintedObjects = ctx.getTaintedObjects()
    builder = addFromTaintFormat(taintedObjects, builder)
    objectHolder.add(builder)
    param = addFromTaintFormat(taintedObjects, param)
    objectHolder.add(param)

    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(result)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    builder = builder?.append(param)
    module.onStringBuilderAppend(builder, param)

    then:
    mockCalls * tracer.activeSpan() >> span
    mockCalls * span.getRequestContext() >> reqCtx
    mockCalls * reqCtx.getData(RequestContextSlot.IAST) >> ctx
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
    builder                     | param                   | mockCalls | expected
    sb('123')                   | null                    | 0         | '123null'
    sb('123')                   | '456'                   | 1         | '123456'
    sb('==>123<==')             | null                    | 0         | '==>123<==null'
    sb('==>123<==')             | '456'                   | 1         | '==>123<==456'
    sb('123')                   | '==>456<=='             | 1         | '123==>456<=='
    sb('==>123<==')             | '==>456<=='             | 1         | '==>123<====>456<=='
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e'           | 1         | '1==>234<==5==>678<==9a==>bcd<==e'
    sb('1==>234<==5==>678<==9') | 'a==>bcd<==e==>fgh<==i' | 1         | '1==>234<==5==>678<==9a==>bcd<==e==>fgh<==i'
  }

  private static StringBuilder sb(final String string) {
    return new StringBuilder(string)
  }
}

