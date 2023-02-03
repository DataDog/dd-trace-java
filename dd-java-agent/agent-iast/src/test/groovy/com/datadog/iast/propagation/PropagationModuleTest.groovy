package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat

class PropagationModuleTest extends IastModuleImplTestBase {

  private PropagationModule module

  private List<Object> objectHolder

  def setup() {
    module = registerDependencies(new PropagationModuleImpl())
    objectHolder = []
  }

  void 'taintIfInputIsTainted null or empty (#param1, #param2)'(final Object param1, final Object param2) {
    when:
    module.taintIfInputIsTainted(param1, param2)

    then:
    0 * _

    where:
    param1       | param2
    null         | null
    ''           | null
    ''           | new Object()
    null         | new Object()
    'test'       | null
    null         | 'test'
    new Object() | ''
    new Object() | null
  }

  void 'taintIfInputIsTainted without span (#param1, #param2)'(final Object param1, final Object param2) {
    when:
    module.taintIfInputIsTainted(param1, param2)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    param1       | param2
    'test'       | new Object()
    new Object() | new Object()
    new Object() | 'test'
  }

  void 'onJsonFactoryCreateParser (#param1, #param2)'(final Object param1, final Object param2) {
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

    def firstParam
    if (param1 instanceof String) {
      firstParam = addFromTaintFormat(taintedObjects, param1)
      objectHolder.add(firstParam)
    } else {
      firstParam = param1
    }

    def secondParam
    if (param2 instanceof String) {
      secondParam = addFromTaintFormat(taintedObjects, param2)
      objectHolder.add(secondParam)
      shouldBeTainted = fromTaintFormat(param2) != null
    } else {
      secondParam = param2
    }

    if (shouldBeTainted) {
      def ranges = new Range[1]
      ranges[0] = new Range(0, Integer.MAX_VALUE, new Source((byte) 1, "test", "test"))
      taintedObjects.taint(secondParam, ranges)
    }


    when:
    module.taintIfInputIsTainted(firstParam, secondParam)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST) >> ctx
    0 * _
    def to = ctx.getTaintedObjects().get(param1)
    if (shouldBeTainted) {
      assert to != null
      assert to.get() == param1
      if (param1 instanceof String) {
        final ranges = to.getRanges()
        assert ranges.length == 1
        assert ranges[0].start == 0
        assert ranges[0].length == param1.length()
      } else {
        final ranges = to.getRanges()
        assert ranges.length == 1
        assert ranges[0].start == 0
        assert ranges[0].length == Integer.MAX_VALUE
      }
    } else {
      assert to == null
    }

    where:
    param1       | param2
    '123'        | new Object()
    new Object() | new Object()
    new Object() | '123'
    new Object() | '==>123<=='
  }
}
