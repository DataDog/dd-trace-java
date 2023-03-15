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

  void 'taintIfInputIsTainted null or empty'() {
    when:
    module.&taintIfInputIsTainted.call(args.toArray())

    then:
    0 * _

    where:
    args                                                                            | _
    [null, null]                                                                    | _
    [null, new Object()]                                                            | _
    [null, 'test']                                                                  | _
    [new Object(), null]                                                            | _
    [null as String, null]                                                          | _
    ['', null]                                                                      | _
    ['', new Object()]                                                              | _
    [null as String, new Object()]                                                  | _
    ['test', null]                                                                  | _
    [null as String, 'test']                                                        | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', null as String, null]             | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', '', null]                         | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', '', new Object()]                 | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', null as String, new Object()]     | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'test', null]                     | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', null as String, 'test']           | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, [].toSet(), 'test']                       | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, ['test'].toSet(), null]                   | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, [:].entrySet().toList(), 'test']          | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, [key: "value"].entrySet().toList(), null] | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', null, 'test']                     | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', [], 'test']                       | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', ['value'], null]                  | _
  }

  void 'taint null or empty'() {
    when:
    module.&taint.call(args.toArray())

    then:
    0 * _

    where:
    args                                                      | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, null as Object[]]   | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, [] as Object[]]     | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, null as Collection] | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, []]                 | _
  }

  void 'taintIfInputIsTainted without span'() {
    when:
    module.&taintIfInputIsTainted.call(args.toArray())

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    args                                                                                    | _
    [new Object(), new Object()]                                                            | _
    [new Object(), 'test']                                                                  | _
    ['test', new Object()]                                                                  | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value', new Object()]                    | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', ['value'], new Object()]                  | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, ['value'].toSet(), new Object()]                  | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, [key: 'value'].entrySet().toList(), new Object()] | _
  }

  void 'taint without span'() {
    when:
    module.&taint.call(args.toArray())

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    args                                                              | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, [new Object()] as Object[]] | _
    [SourceTypes.REQUEST_PARAMETER_VALUE, [new Object()]]             | _
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

  void 'test taintIfInputIsTainted'() {
    when:
    final toTaint = toTaintClosure.call(args)
    module.&taintIfInputIsTainted.call(args as Object[])

    then:
    assertNotTainted(toTaint)

    when:
    taint(args.last())
    module.&taintIfInputIsTainted.call(args as Object[])

    then:
    assertTainted(toTaint)

    where:
    args                                                                                            | toTaintClosure
    [new Object(), 'I am an string']                                                                | { it[0] }
    [new Object(), new Object()]                                                                    | { it[0] }
    [new Object(), new MockTaintable()]                                                             | { it[0] }
    ['Hello', 'I am an string']                                                                     | { it[0] }
    ['Hello', new Object()]                                                                         | { it[0] }
    ['Hello', new MockTaintable()]                                                                  | { it[0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value', 'I am an string']                        | { it[2] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value', new Object()]                            | { it[2] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value', new MockTaintable()]                     | { it[2] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', ['value'], 'I am an string']                      | { it[2][0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', ['value'], new Object()]                          | { it[2][0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, 'name', ['value'], new MockTaintable()]                   | { it[2][0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, ['value'].toSet(), 'I am an string']                      | { it[1][0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, ['value'].toSet(), new Object()]                          | { it[1][0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, ['value'].toSet(), new MockTaintable()]                   | { it[1][0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, [name: 'value'].entrySet().toList(), 'I am an string']    | { it[1][0].value }
    [SourceTypes.REQUEST_PARAMETER_VALUE, [name: 'value'].entrySet().toList(), new Object()]        | { it[1][0].value }
    [SourceTypes.REQUEST_PARAMETER_VALUE, [name: 'value'].entrySet().toList(), new MockTaintable()] | { it[1][0].value }
  }

  void 'test taint'() {
    when:
    module.&taint.call(args as Object[])

    then:
    final toTaint = toTaintClosure.call(args)
    assertTainted(toTaint)

    where:
    args                                                         | toTaintClosure
    [SourceTypes.REQUEST_PARAMETER_VALUE, new Object()]          | { it[1] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, [new Object()]]        | { it[1][0] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, new MockTaintable()]   | { it[1] }
    [SourceTypes.REQUEST_PARAMETER_VALUE, [new MockTaintable()]] | { it[1][0] }
  }

  void 'test lazy tainted objects'() {
    given:
    final to = PropagationModuleImpl.lazyTaintedObjects()
    final tainted = 'I am tainted'

    when:
    to.taintInputString(tainted, new Source(SourceTypes.REQUEST_COOKIE_VALUE, null, null))

    then:
    ctx.taintedObjects.get(tainted) != null
    to.estimatedSize == ctx.taintedObjects.estimatedSize
    to.flat == ctx.taintedObjects.flat

    when:
    to.release()

    then:
    ctx.taintedObjects.estimatedSize == 0
  }

  private <E> E taint(final E toTaint) {
    final source = new Source(SourceTypes.REQUEST_PARAMETER_VALUE, null, null)
    if (toTaint instanceof Taintable) {
      toTaint.$$DD$setSource(source)
    } else {
      ctx.taintedObjects.taintInputObject(toTaint, source)
      objectHolder.add(toTaint)
    }
    return toTaint
  }

  private void assertTainted(final Object toTaint) {
    final tainted = ctx.getTaintedObjects().get(toTaint)
    if (toTaint instanceof Taintable) {
      assert tainted == null
      assert toTaint.$$DD$getSource() != null
    } else {
      assert tainted != null
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
