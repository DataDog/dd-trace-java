package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat
import static com.datadog.iast.taint.TaintUtils.getStringFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class IastModuleImplOnStringSubSequenceTest extends IastModuleImplTestBase {

  @Shared
  private List<Object> objectHolder = []

  def setup() {
    objectHolder.clear()
  }

  void 'onStringSubSequence null ,empty or string not change after subsequence (#self, #beginIndex, #endIndex)'(final String self, final int beginIndex, final int endIndex) {
    given:
    final result = self?.substring(beginIndex, endIndex)

    when:
    module.onStringSubSequence(self, beginIndex, endIndex, result)

    then:
    0 * _

    where:
    self         | beginIndex | endIndex
    ""           | 0          | 0
    null         | 0          | 0
    "not_change" | 0          | 10
  }

  void 'onStringSubSequence without span (#self, #beginIndex, #endIndex)'(final String self, final int beginIndex, final int endIndex, final int mockCalls) {
    given:
    final result = self?.substring(beginIndex, endIndex)

    when:
    module.onStringSubSequence(self, beginIndex, endIndex, result)

    then:
    mockCalls * tracer.activeSpan() >> null
    0 * _

    where:
    self  | beginIndex | endIndex | mockCalls
    ""    | 0          | 0        | 0
    null  | 0          | 0        | 0
    "123" | 1          | 2        | 1
  }

  void 'onStringSubSequence (#self, #beginIndex, #endIndex)'() {
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span
    final reqCtx = Mock(RequestContext)
    span.getRequestContext() >> reqCtx
    final ctx = new IastRequestContext()
    reqCtx.getData(RequestContextSlot.IAST) >> ctx

    and:
    final taintedObjects = ctx.getTaintedObjects()
    self = addFromTaintFormat(taintedObjects, self)
    objectHolder.add(self)


    and:
    final result = getStringFromTaintFormat(expected)
    objectHolder.add(expected)
    final shouldBeTainted = fromTaintFormat(expected) != null

    when:
    module.onStringSubSequence(self, beginIndex, endIndex, result)

    then:
    assert result == getStringFromTaintFormat(self).substring(beginIndex, endIndex)

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
    self                      | beginIndex | endIndex | expected
    "0123==>456<==78"         | 1          | 8        | "123==>456<==7"
    "0123==>456<==78"         | 0          | 4        | "0123"
    "0123==>456<==78"         | 7          | 9        | "78"
    "0123==>456<==78"         | 1          | 5        | "123==>4<=="
    "0123==>456<==78"         | 1          | 6        | "123==>45<=="
    "0123==>456<==78"         | 4          | 7        | "==>456<=="
    "0123==>456<==78"         | 6          | 8        | "==>6<==7"
    "0123==>456<==78"         | 5          | 8        | "==>56<==7"
    "0123==>456<==78"         | 4          | 6        | "==>45<=="
    "01==>234<==5==>678<==90" | 1          | 10       | "1==>234<==5==>678<==9"
    "01==>234<==5==>678<==90" | 1          | 2        | "1"
    "01==>234<==5==>678<==90" | 5          | 6        | "5"
    "01==>234<==5==>678<==90" | 9          | 10       | "9"
    "01==>234<==5==>678<==90" | 1          | 4        | "1==>23<=="
    "01==>234<==5==>678<==90" | 2          | 4        | "==>23<=="
    "01==>234<==5==>678<==90" | 2          | 5        | "==>234<=="
    "01==>234<==5==>678<==90" | 1          | 8        | "1==>234<==5==>67<=="
    "01==>234<==5==>678<==90" | 2          | 8        | "==>234<==5==>67<=="
    "01==>234<==5==>678<==90" | 2          | 9        | "==>234<==5==>678<=="
    "01==>234<==5==>678<==90" | 5          | 8        | "5==>67<=="
    "01==>234<==5==>678<==90" | 6          | 8        | "==>67<=="
    "01==>234<==5==>678<==90" | 6          | 9        | "==>678<=="
    "01==>234<==5==>678<==90" | 4          | 9        | "==>4<==5==>678<=="
    "01==>234<==5==>678<==90" | 4          | 8        | "==>4<==5==>67<=="
  }
}
