package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.fromTaintFormat

@CompileDynamic
class PropagationModuleTest extends IastModuleImplTestBase {

  private PropagationModule module
  private List<Object> objectHolder
  private IastRequestContext ctx

  def setup() {
    module = new PropagationModuleImpl()
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    final span = Mock(AgentSpan) {
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
  }

  void 'taintIfInputIsTainted(Object, Object) null or empty'() {
    when:
    module.taintIfInputIsTainted(param1, param2)

    then:
    0 * _

    where:
    param1       | param2
    null         | null
    null         | new Object()
    null         | 'test'
    new Object() | null
  }

  void 'taintIfInputIsTainted(String, Object) null or empty'() {
    when:
    module.taintIfInputIsTainted(param1, param2)

    then:
    0 * _

    where:
    param1 | param2
    null   | null
    ''     | null
    ''     | new Object()
    null   | new Object()
    'test' | null
    null   | 'test'
  }

  void 'taintIfInputIsTainted(byte, String, String, Object) null or empty'() {
    when:
    module.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', param1, param2)

    then:
    0 * _

    where:
    param1 | param2
    null   | null
    ''     | null
    ''     | new Object()
    null   | new Object()
    'test' | null
    null   | 'test'
  }

  void 'taintIfInputIsTainted(Object, Object) without span'() {
    when:
    module.taintIfInputIsTainted(param1, param2)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    param1       | param2
    new Object() | new Object()
    new Object() | 'test'
  }

  void 'taintIfInputIsTainted(String, Object) without span'() {
    when:
    module.taintIfInputIsTainted(param1 as String, param2)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    param1 | param2
    'test' | new Object()
  }

  void 'taintIfInputIsTainted(byte, String, String, Object) without span'() {
    when:
    module.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', param1, param2)

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    param1 | param2
    'test' | new Object()
  }

  void 'onJsonFactoryCreateParser'() {
    given:
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

  void 'taintIfInputIsTainted(Object, Object)'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    if (inputTainted) {
      taint(input, origin)
    }

    when:
    module.taintIfInputIsTainted(toTaint, input)

    then:
    if (inputTainted) {
      assertTainted(toTaint, origin)
    } else {
      assertNotTainted(toTaint)
    }

    where:
    toTaint             | input               | inputTainted
    new Object()        | new MockTaintable() | false
    new MockTaintable() | new MockTaintable() | false
    new Object()        | new MockTaintable() | true
    new MockTaintable() | new MockTaintable() | true
    new Object()        | new Object()        | false
    new MockTaintable() | new Object()        | false
    new Object()        | new Object()        | true
    new MockTaintable() | new Object()        | true
  }

  void 'taintIfInputIsTainted(String, Object)'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    if (inputTainted) {
      taint(input, origin)
    }

    when:
    module.taintIfInputIsTainted(toTaint, input)

    then:
    if (inputTainted) {
      assertTainted(toTaint, origin)
    } else {
      assertNotTainted(toTaint)
    }

    where:
    toTaint | input               | inputTainted
    'Hello' | new MockTaintable() | false
    'Hello' | new MockTaintable() | true
    'Hello' | new Object()        | false
    'Hello' | new Object()        | true
  }

  void 'taintIfInputIsTainted(byte, String, String, Object)'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE
    if (inputTainted) {
      taint(input, origin)
    }

    when:
    module.taintIfInputIsTainted(origin, 'test', toTaint, input)

    then:
    if (inputTainted) {
      assertTainted(toTaint, origin)
    } else {
      assertNotTainted(toTaint)
    }

    where:
    toTaint | input               | inputTainted
    'Hello' | new MockTaintable() | false
    'Hello' | new MockTaintable() | true
    'Hello' | new Object()        | false
    'Hello' | new Object()        | true
  }

  void 'taint(byte, Object...)'() {
    given:
    final origin = SourceTypes.REQUEST_PARAMETER_VALUE

    when:
    module.taint(origin, [toTaint] as Object[])

    then:
    assertTainted(toTaint, origin)

    where:
    _ | toTaint
    _ | 'Hello'
    _ | new Object()
    _ | new MockTaintable()
  }

  private Object taint(final Object toTaint, final byte origin) {
    final source = new Source(origin, null, null)
    if (toTaint instanceof Taintable) {
      toTaint.$$DD$setSource(source)
    } else {
      ctx.taintedObjects.taintInputObject(toTaint, source)
      objectHolder.add(toTaint)
    }
    return toTaint
  }

  private void assertTainted(final Object toTaint, final byte origin) {
    final tainted = ctx.getTaintedObjects().get(toTaint)
    if (toTaint instanceof Taintable) {
      assert tainted == null
      assert toTaint.$$DD$getSource().origin == origin
    } else {
      assert tainted != null
      assert tainted.ranges.first().source.origin == origin
    }
  }

  private void assertNotTainted(final Object toTaint) {
    final tainted = ctx.getTaintedObjects().get(toTaint)
    assert tainted == null
    if (toTaint instanceof Taintable) {
      assert toTaint.$$DD$getSource() == null
    }
  }

  /**
   * Mocking makes the test a bit more confusing
   */
  private static final class MockTaintable implements Taintable {

    private Source source

    @Override
    @SuppressWarnings('CodeNarc')
    Source $$DD$getSource() {
      return source
    }

    @Override
    @SuppressWarnings('CodeNarc')
    void $$DD$setSource(Source source) {
      this.source = source
    }
  }
}
