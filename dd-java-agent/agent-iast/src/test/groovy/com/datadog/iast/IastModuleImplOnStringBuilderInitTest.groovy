package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringBuilderInitTest extends IastModuleImplTestBase {

  @Shared
  private List<Object> objectHolder = []

  def setup() {
    objectHolder.clear()
  }

  void 'onStringBuilderInit null or empty (#builder, #param)'(final StringBuilder builder, final String param) {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderInit(result, param)

    then:
    0 * _

    where:
    builder | param
    sb('')  | null
    sb('')  | ''
  }

  void 'onStringBuilderInit without span (#builder, #param)'(final StringBuilder builder, final String param, final int mockCalls) {
    given:
    final result = builder?.append(param)

    when:
    module.onStringBuilderInit(result, param)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    builder | param | mockCalls
    sb()    | null  | 0
    sb()    | '4'   | 1
  }

  void 'onStringBuilderInit (#builder, #param)'(StringBuilder builder, String param, final int mockCalls, final String expected) {
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
    module.onStringBuilderInit(builder, param)

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
    builder | param                   | mockCalls | expected
    sb()    | null                    | 0         | 'null'
    sb()    | '123'                   | 1         | '123'
    sb()    | '==>123<=='             | 1         | '==>123<=='
    sb()    | 'a==>bcd<==e==>fgh<==i' | 1         | 'a==>bcd<==e==>fgh<==i'
  }

  private static StringBuilder sb() {
    return sb("")
  }

  private static StringBuilder sb(final String string) {
    return new StringBuilder(string)
  }
}

