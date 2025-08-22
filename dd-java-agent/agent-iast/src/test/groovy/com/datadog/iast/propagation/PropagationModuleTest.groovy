package com.datadog.iast.propagation

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.Ranges

import com.datadog.iast.taint.TaintedObject
import datadog.trace.api.Config
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Shared

import java.lang.ref.Reference

import static org.junit.jupiter.api.Assumptions.assumeFalse
import static com.datadog.iast.taint.Ranges.highestPriorityRange
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class PropagationModuleTest extends IastModuleImplTestBase {

  @Shared
  private static int maxValueLength = Config.get().iastTruncationMaxValueLength

  private PropagationModule module

  void setup() {
    module = new PropagationModuleImpl()
  }

  @Override
  protected AgentTracer.TracerAPI buildAgentTracer() {
    return Mock(AgentTracer.TracerAPI) {
      activeSpan() >> span
      getTraceSegment() >> traceSegment
    }
  }

  void '#method(#argTypes) not taintable'() {
    when: 'there is no context by default'
    module.&"$method".call(args.toArray())

    then: 'no mock calls should happen'
    0 * _

    when: 'there is a context'
    args.add(0, ctx)
    module.&"$method".call(args.toArray())

    then: 'no mock calls should happen'
    0 * _

    where:
    method                      | args
    'taintObject'               | [null, SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintString'               | [null, SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintObject'               | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintString'               | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintObject'               | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintString'               | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintObjectRange'          | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 0, 10]
    'taintStringRange'          | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 0, 10]
    'taintObjectIfTainted'      | [null, 'test']
    'taintStringIfTainted'      | [null, 'test']
    'taintObjectIfTainted'      | [date(), null]
    'taintStringIfTainted'      | ['test', null]
    'taintObjectIfTainted'      | [null, 'test', false, NOT_MARKED]
    'taintStringIfTainted'      | [null, 'test', false, NOT_MARKED]
    'taintObjectIfTainted'      | [date(), null, false, NOT_MARKED]
    'taintStringIfTainted'      | ['test', null, false, NOT_MARKED]
    'taintObjectIfRangeTainted' | [null, 'test', 0, 4, false, NOT_MARKED]
    'taintStringIfRangeTainted' | [null, 'test', 0, 4, false, NOT_MARKED]
    'taintObjectIfRangeTainted' | [date(), null, 0, 4, false, NOT_MARKED]
    'taintStringIfRangeTainted' | ['test', null, 0, 4, false, NOT_MARKED]
    'taintObjectIfTainted'      | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintStringIfTainted'      | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintObjectIfTainted'      | [date(), null, SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintStringIfTainted'      | ['test', null, SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintObjectIfTainted'      | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintStringIfTainted'      | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintObjectIfTainted'      | [date(), null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintStringIfTainted'      | ['test', null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintObjectIfTainted'      | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintStringIfTainted'      | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintObjectIfTainted'      | [date(), null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintStringIfTainted'      | ['test', null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintObjectIfAnyTainted'   | [null, ['test'] as Object[]]
    'taintStringIfAnyTainted'   | [null, ['test'] as Object[]]
    'taintObjectIfAnyTainted'   | [date(), null]
    'taintStringIfAnyTainted'   | ['test', null]
    'taintObjectIfAnyTainted'   | [date(), [] as Object[]]
    'taintStringIfAnyTainted'   | ['test', [] as Object[]]
    'taintObjectDeeply'         | [
      null,
      SourceTypes.REQUEST_PARAMETER_VALUE,
      {
        true
      }
    ]
    'findSource'                | [null]
    'isTainted'                 | [null]
    argTypes = args*.class.name
  }

  void '#method without span'() {
    when:
    module.&"$method".call(args.toArray())

    then:
    1 * tracer.activeSpan() >> null
    0 * _

    where:
    method                      | args
    'taintObject'               | [date(), SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintString'               | ['test', SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintObject'               | [date(), SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintString'               | ['test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintObject'               | [date(), SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintString'               | ['test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintObjectRange'          | [date(), SourceTypes.REQUEST_PARAMETER_VALUE, 0, 10]
    'taintStringRange'          | ['test', SourceTypes.REQUEST_PARAMETER_VALUE, 0, 10]
    'taintObjectIfTainted'      | [date(), 'test']
    'taintStringIfTainted'      | ['test', 'test']
    'taintObjectIfRangeTainted' | [date(), 'test', 0, 4, false, NOT_MARKED]
    'taintStringIfRangeTainted' | ['test', 'test', 0, 4, false, NOT_MARKED]
    'taintObjectIfTainted'      | [date(), 'test', false, NOT_MARKED]
    'taintStringIfTainted'      | ['test', 'test', false, NOT_MARKED]
    'taintObjectIfTainted'      | [date(), 'test', SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintStringIfTainted'      | ['test', 'test', SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintObjectIfTainted'      | [date(), 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintStringIfTainted'      | ['test', 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintObjectIfTainted'      | [date(), 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintStringIfTainted'      | ['test', 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintObjectIfAnyTainted'   | [date(), ['test']]
    'taintStringIfAnyTainted'   | ['test', ['test']]
    'taintObjectDeeply'         | [
      'test',
      SourceTypes.REQUEST_PARAMETER_VALUE,
      {
        true
      }
    ]
    'findSource'                | ['test']
    'isTainted'                 | ['test']
  }

  void 'test taint'() {
    given:
    final value = (target instanceof CharSequence) ? target.toString() : null
    final source = taintedSource(value)
    final ranges = Ranges.forObject(source)

    when:
    module."$method"(target, source.origin, source.name, source.value)

    then:
    final tainted = getTaintedObject(target)
    if (shouldTaint) {
      assertTainted(tainted, ranges)
    } else {
      assert tainted == null
    }

    where:
    method        | target                         | shouldTaint
    'taintString' | string('string')               | true
    'taintObject' | stringBuilder('stringBuilder') | true
    'taintObject' | date()                         | true
    'taintObject' | taintable()                    | true
  }

  void 'test taint with range'() {
    given:
    final value = (target instanceof CharSequence) ? target.toString() : null
    final source = new Source(SourceTypes.REQUEST_PARAMETER_VALUE, null, value)
    final ranges = [new Range(start, length, source, NOT_MARKED)] as Range[]

    when:
    module."$method"(target, source.origin, start, length)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, ranges)

    where:
    method             | target                         | start | length
    'taintStringRange' | string('string')               | 0     | 2
    'taintObjectRange' | stringBuilder('stringBuilder') | 0     | 2
    'taintObjectRange' | date()                         | 0     | 2
    'taintObjectRange' | taintable()                    | 0     | 2
  }

  void 'test taintIfTainted keeping ranges'() {
    given:
    def (type, target, input) = suite
    final method = "taint${type}IfTainted"
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module."$method"(target, input, true, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, input, true, NOT_MARKED)

    then:
    final tainted = getTaintedObject(target)
    if (target instanceof Taintable) {
      // only first range is kept
      assertTainted(tainted, [taintedFrom.ranges[0]] as Range[])
    } else {
      assertTainted(tainted, taintedFrom.ranges)
    }

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfTainted with ranges'() {
    given:
    def (type, target, input) = suite
    final method = "taint${type}IfRangeTainted"
    final source = taintedSource()
    final ranges = [new Range(0, 2, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module."$method"(target, input, 0, 2, false, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input tainted but range does not overlap'
    final firstTaintedForm = taintObject(input, ranges)
    module."$method"(target, input, 4, 3, false, NOT_MARKED)

    then:
    final firstTainted = getTaintedObject(target)
    if (input instanceof Taintable) {
      // Taintable has no ranges so it will always overlap
      assertTainted(firstTainted, [firstTaintedForm.ranges[0]] as Range[])
    } else {
      assert firstTainted == null
    }

    when: 'input is tainted and range overlaps'
    ctx.taintedObjects.clear()
    final secondTaintedFrom = taintObject(input, ranges)
    module."$method"(target, input, 0, 2, false, NOT_MARKED)

    then:
    final secondTainted = getTaintedObject(target)
    if (target instanceof Taintable) {
      // only first range is kept
      assertTainted(secondTainted, [secondTaintedFrom.ranges[0]] as Range[])
    } else {
      assertTainted(secondTainted, secondTaintedFrom.ranges)
    }

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfTainted keeping ranges with a mark'() {
    given:
    def (type, target, input) = suite
    assumeFalse(target instanceof Taintable) // taintable does not support multiple ranges or marks
    final method = "taint${type}IfTainted"
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.UNVALIDATED_REDIRECT_MARK

    when: 'input is not tainted'
    module."$method"(target, input, true, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, input, true, mark)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, taintedFrom.ranges, mark)

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfTainted not keeping ranges'() {
    given:
    def (type, target, input) = suite
    final method = "taint${type}IfTainted"
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module."$method"(target, input, false, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, input, false, NOT_MARKED)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, [highestPriorityRange(taintedFrom.ranges)] as Range[])

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfTainted not keeping ranges with a mark'() {
    given:
    def (type, target, input) = suite
    assumeFalse(target instanceof Taintable) // taintable does not support marks
    final method = "taint${type}IfTainted"
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.LDAP_INJECTION_MARK

    when: 'input is not tainted'
    module."$method"(target, input, false, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, input, false, mark)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, [highestPriorityRange(taintedFrom.ranges)] as Range[], mark)

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfAnyTainted keeping ranges'() {
    given:
    def (type, target, input) = suite
    final method = "taint${type}IfAnyTainted"
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module."$method"(target, inputs, true, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, inputs, true, NOT_MARKED)

    then:
    final tainted = getTaintedObject(target)
    if (target instanceof Taintable) {
      // only first range is kept
      assertTainted(tainted, [taintedFrom.ranges[0]] as Range[])
    } else {
      assertTainted(tainted, taintedFrom.ranges)
    }

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfAnyTainted keeping ranges with a mark'() {
    given:
    def (type, target, input) = suite
    assumeFalse(target instanceof Taintable) // taintable does not support multiple ranges or marks
    final method = "taint${type}IfAnyTainted"
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.UNVALIDATED_REDIRECT_MARK

    when: 'input is not tainted'
    module."$method"(target, inputs, true, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, inputs, true, mark)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, taintedFrom.ranges, mark)

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfAnyTainted not keeping ranges'() {
    given:
    def (type, target, input) = suite
    final method = "taint${type}IfAnyTainted"
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module."$method"(target, inputs, false, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, inputs, false, NOT_MARKED)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, [highestPriorityRange(taintedFrom.ranges)] as Range[])

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfAnyTainted not keeping ranges with a mark'() {
    given:
    def (type, target, input) = suite
    assumeFalse(target instanceof Taintable) // taintable does not support marks
    final method = "taint${type}IfAnyTainted"
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.LDAP_INJECTION_MARK

    when: 'input is not tainted'
    module."$method"(target, inputs, false, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module."$method"(target, inputs, false, mark)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, [highestPriorityRange(taintedFrom.ranges)] as Range[], mark)

    where:
    suite << taintIfSuite()
  }

  void 'test taint deeply'() {
    given:
    final target = [Hello: " World!", Age: 25]

    when:
    module.taintObjectDeeply(target, SourceTypes.GRPC_BODY, { true })

    then:
    final taintedObjects = ctx.taintedObjects
    target.keySet().each { key ->
      assert taintedObjects.get(key) != null
    }
    assert taintedObjects.get(target['Hello']) != null
    assert taintedObjects.size() == 3 // two keys and one string value
  }

  void 'test taint deeply char sequence'() {
    given:
    final target = stringBuilder('taint me')

    when:
    module.taintObjectDeeply(target, SourceTypes.GRPC_BODY, { true })

    then:
    final taintedObjects = ctx.taintedObjects
    assert taintedObjects.size() == 1
    final tainted = taintedObjects.get(target)
    assert tainted != null
    final source = tainted.ranges[0].source
    assert source.origin == SourceTypes.GRPC_BODY
    assert source.value == target.toString()
  }

  void 'test is tainted and find source'() {
    given:
    if (source != null) {
      taintObject(target, source)
    }

    when:
    final tainted = module.isTainted(target)

    then:
    tainted == (source != null)

    when:
    final foundSource = module.findSource(target)

    then:
    foundSource == source

    where:
    target                         | source
    string('string')               | null
    stringBuilder('stringBuilder') | null
    date()                         | null
    taintable()                    | null
    string('string')               | taintedSource()
    stringBuilder('stringBuilder') | taintedSource()
    date()                         | taintedSource()
    taintable()                    | taintedSource()
  }

  void 'test source names over threshold'() {
    given:
    assert target.length() > maxValueLength
    final method = target instanceof String ? 'taintString' : 'taintObject'

    when:
    module."$method"(target, SourceTypes.REQUEST_PARAMETER_VALUE)

    then:
    final tainted = ctx.getTaintedObjects().get(target)
    tainted != null
    final sourceValue = tainted.ranges.first().source.value
    if (resize) {
      sourceValue.length() == maxValueLength
    } else {
      sourceValue.length() == target.length()
    }

    where:
    target                                          | resize
    string((0..maxValueLength * 2).join(''))        | false // a reference to the string is created
    stringBuilder((0..maxValueLength * 2).join('')) | true  // we create a copy
  }

  void 'test that source names/values should not make a strong reference over the value'() {
    given:
    final method = toTaint instanceof String ? 'taintString' : 'taintObject'

    when:
    module."$method"(toTaint, SourceTypes.REQUEST_PARAMETER_NAME, name, value)

    then:
    final tainted = ctx.getTaintedObjects().get(toTaint)
    final source = tainted.ranges.first().source
    final sourceName = source.@name
    final sourceValue = source.@value

    assert sourceName !== toTaint: 'Source name should never be a strong reference to the tainted value'
    assert sourceValue !== toTaint: 'Source value should never be a strong reference to the tainted value'

    switch (value.class) {
      case String:
        assert sourceValue instanceof Reference
        assert (sourceValue as Reference).get() === value
        break
      case CharSequence:
        assert sourceValue instanceof String
        assert sourceValue == value.toString()
        break
      default:
        assert sourceValue === Source.PROPAGATION_PLACEHOLDER
        break
    }
    if (name === value) {
      assert sourceName === sourceValue
    } else {
      assert sourceName !== sourceValue
      assert sourceName === name
    }

    where:
    name           | value                 | toTaint
    string('name') | name                  | string('name')
    string('name') | stringBuilder('name') | stringBuilder('name')
    string('name') | date()                | date()
    string('name') | name                  | value
    string('name') | stringBuilder('name') | value
    string('name') | date()                | value
  }

  void 'test propagation of the source value for non char sequences'() {
    given:
    final toTaint = 'hello'
    final baos = toTaint.bytes

    when: 'tainting a non char sequence object'
    module.taintObject(baos, SourceTypes.KAFKA_MESSAGE_KEY)

    then:
    with(ctx.taintedObjects.get(baos)) {
      assert ranges.length == 1
      final source = ranges.first().source
      assert source.origin == SourceTypes.KAFKA_MESSAGE_KEY
      assert source.@value === Source.PROPAGATION_PLACEHOLDER
      assert source.value == null
    }

    when: 'the object is propagated'
    module.taintStringIfTainted(toTaint, baos)

    then:
    with(ctx.taintedObjects.get(toTaint)) {
      assert ranges.length == 1
      final source = ranges.first().source
      assert source.origin == SourceTypes.KAFKA_MESSAGE_KEY
      assert source.value == toTaint
    }
  }

  private List<Tuple<Object>> taintIfSuite() {
    return [
      Tuple.tuple("String", string('string'), string('string')),
      Tuple.tuple("String", string('string'), stringBuilder('stringBuilder')),
      Tuple.tuple("String", string('string'), date()),
      Tuple.tuple("String", string('string'), taintable()),
      Tuple.tuple("Object", stringBuilder('stringBuilder'), string('string')),
      Tuple.tuple("Object", stringBuilder('stringBuilder'), stringBuilder('stringBuilder')),
      Tuple.tuple("Object", stringBuilder('stringBuilder'), date()),
      Tuple.tuple("Object", stringBuilder('stringBuilder'), taintable()),
      Tuple.tuple("Object", date(), string('string')),
      Tuple.tuple("Object", date(), stringBuilder('stringBuilder')),
      Tuple.tuple("Object", date(), date()),
      Tuple.tuple("Object", date(), taintable()),
      Tuple.tuple("Object", taintable(), string('string')),
      Tuple.tuple("Object", taintable(), stringBuilder('stringBuilder')),
      Tuple.tuple("Object", taintable(), date()),
      Tuple.tuple("Object", taintable(), taintable())
    ]
  }

  private TaintedObject getTaintedObject(final Object target) {
    if (target instanceof Taintable) {
      final source = (target as Taintable).$$DD$getSource() as Source
      return source == null ? null : new TaintedObject(target, Ranges.forObject(source))
    }
    return ctx.getTaintedObjects().get(target)
  }

  private TaintedObject taintObject(final Object target, Source source, int mark = NOT_MARKED) {
    if (target instanceof Taintable) {
      target.$$DD$setSource(source)
    } else if (target instanceof CharSequence) {
      ctx.getTaintedObjects().taint(target, Ranges.forCharSequence(target, source, mark))
    } else {
      ctx.getTaintedObjects().taint(target, Ranges.forObject(source, mark))
    }
    return getTaintedObject(target)
  }

  private TaintedObject taintObject(final Object target, Range[] ranges) {
    if (target instanceof Taintable) {
      target.$$DD$setSource(ranges[0].getSource())
    } else {
      ctx.getTaintedObjects().taint(target, ranges)
    }
    return getTaintedObject(target)
  }

  private String string(String value, Source source = null, int mark = NOT_MARKED) {
    final result = new String(value)
    if (source != null) {
      taintObject(result, source, mark)
    }
    return result
  }

  private StringBuilder stringBuilder(String value, Source source = null, int mark = NOT_MARKED) {
    final result = new StringBuilder(value)
    if (source != null) {
      taintObject(result, source, mark)
    }
    return result
  }

  private Date date(Source source = null, int mark = NOT_MARKED) {
    final result = new Date(1234567890) // Use a fixed date
    if (source != null) {
      taintObject(result, source, mark)
    }
    return result
  }

  private Taintable taintable(Source source = null) {
    final result = new MockTaintable()
    if (source != null) {
      taintObject(result, source)
    }
    return result
  }

  private Source taintedSource(String value = 'value') {
    return new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', value)
  }

  private static void assertTainted(final TaintedObject tainted, final Range[] ranges, final int mark = NOT_MARKED) {
    assert tainted != null
    assert tainted.ranges.length == ranges.length
    ranges.eachWithIndex { Range expected, int i ->
      final range = tainted.ranges[i]
      if (mark == NOT_MARKED) {
        assert range.marks == expected.marks
      } else {
        assert (range.marks & mark) > 0
      }
      final source = range.source
      final expectedSource = expected.source
      assert source.origin == expectedSource.origin
      assert source.name == expectedSource.name
      assert source.value == expectedSource.value
    }
  }

  private static class MockTaintable implements Taintable {
    private Source source

    @SuppressWarnings('CodeNarc')
    @Override
    Source $$DD$getSource() {
      return source
    }

    @SuppressWarnings('CodeNarc')
    @Override
    void $$DD$setSource(Source source) {
      this.source = source
    }

    @Override
    String toString() {
      return Taintable.name
    }
  }
}
