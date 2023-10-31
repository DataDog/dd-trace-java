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
import org.junit.Assume

import static com.datadog.iast.taint.Ranges.highestPriorityRange
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class PropagationModuleTest extends IastModuleImplTestBase {

  private PropagationModuleImpl module

  void setup() {
    module = new PropagationModuleImpl(dependencies)
  }

  void '#method(#args) not taintable'() {
    when:
    module.&"$method".call(args.toArray())

    then:
    0 * _

    where:
    method              | args
    'taint'             | [null, SourceTypes.REQUEST_PARAMETER_VALUE]
    'taint'             | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taint'             | [null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintIfTainted'    | [null, 'test']
    'taintIfTainted'    | ['test', null]
    'taintIfTainted'    | [null, 'test', false, NOT_MARKED]
    'taintIfTainted'    | ['test', null, false, NOT_MARKED]
    'taintIfTainted'    | [null, 'test']
    'taintIfTainted'    | ['test', null]
    'taintIfTainted'    | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintIfTainted'    | ['test', null, SourceTypes.REQUEST_PARAMETER_VALUE]
    'taintIfTainted'    | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintIfTainted'    | ['test', null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name']
    'taintIfTainted'    | [null, 'test', SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintIfTainted'    | ['test', null, SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value']
    'taintIfAnyTainted' | [null, ['test'] as Object[]]
    'taintIfAnyTainted' | ['test', null]
    'taintIfAnyTainted' | ['test', [] as Object[]]
    'taintDeeply'       | [
      null,
      SourceTypes.REQUEST_PARAMETER_VALUE,
      {
        true
      }
    ]
    'findSource'        | [null]
    'isTainted'         | [null]
  }

  void 'test taint'() {
    given:
    final value = (target instanceof CharSequence) ? target.toString() : null
    final source = taintedSource(value)
    final ranges = Ranges.forObject(source)

    when:
    module.taint(target, source.origin, source.name, source.value)

    then:
    final tainted = getTaintedObject(target)
    if (shouldTaint) {
      assertTainted(tainted, ranges)
    } else {
      assert tainted == null
    }

    where:
    target                         | shouldTaint
    string('string')               | true
    stringBuilder('stringBuilder') | true
    date()                         | true
    taintable()                    | true
  }

  void 'test taintIfTainted keeping ranges'() {
    given:
    def (target, input) = suite
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module.taintIfTainted(target, input, true, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfTainted(target, input, true, NOT_MARKED)

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

  void 'test taintIfTainted keeping ranges with a mark'() {
    given:
    def (target, input) = suite
    Assume.assumeFalse(target instanceof Taintable) // taintable does not support multiple ranges or marks
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.UNVALIDATED_REDIRECT_MARK

    when: 'input is not tainted'
    module.taintIfTainted(target, input, true, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfTainted(target, input, true, mark)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, taintedFrom.ranges, mark)

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfTainted not keeping ranges'() {
    given:
    def (target, input) = suite
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module.taintIfTainted(target, input, false, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfTainted(target, input, false, NOT_MARKED)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, [highestPriorityRange(taintedFrom.ranges)] as Range[])

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfTainted not keeping ranges with a mark'() {
    given:
    def (target, input) = suite
    Assume.assumeFalse(target instanceof Taintable) // taintable does not support marks
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.LDAP_INJECTION_MARK

    when: 'input is not tainted'
    module.taintIfTainted(target, input, false, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfTainted(target, input, false, mark)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, [highestPriorityRange(taintedFrom.ranges)] as Range[], mark)

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfAnyTainted keeping ranges'() {
    given:
    def (target, input) = suite
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module.taintIfAnyTainted(target, inputs, true, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfAnyTainted(target, inputs, true, NOT_MARKED)

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
    def (target, input) = suite
    Assume.assumeFalse(target instanceof Taintable) // taintable does not support multiple ranges or marks
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.UNVALIDATED_REDIRECT_MARK

    when: 'input is not tainted'
    module.taintIfAnyTainted(target, inputs, true, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfAnyTainted(target, inputs, true, mark)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, taintedFrom.ranges, mark)

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfAnyTainted not keeping ranges'() {
    given:
    def (target, input) = suite
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]

    when: 'input is not tainted'
    module.taintIfAnyTainted(target, inputs, false, NOT_MARKED)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfAnyTainted(target, inputs, false, NOT_MARKED)

    then:
    final tainted = getTaintedObject(target)
    assertTainted(tainted, [highestPriorityRange(taintedFrom.ranges)] as Range[])

    where:
    suite << taintIfSuite()
  }

  void 'test taintIfAnyTainted not keeping ranges with a mark'() {
    given:
    def (target, input) = suite
    Assume.assumeFalse(target instanceof Taintable) // taintable does not support marks
    final inputs = ['test', input].toArray()
    final source = taintedSource()
    final ranges = [new Range(0, 1, source, NOT_MARKED), new Range(1, 1, source, NOT_MARKED)] as Range[]
    final mark = VulnerabilityMarks.LDAP_INJECTION_MARK

    when: 'input is not tainted'
    module.taintIfAnyTainted(target, inputs, false, mark)

    then:
    assert getTaintedObject(target) == null

    when: 'input is tainted'
    final taintedFrom = taintObject(input, ranges)
    module.taintIfAnyTainted(target, inputs, false, mark)

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
    module.taintDeeply(target, SourceTypes.GRPC_BODY, { true })

    then:
    target.keySet().each { key ->
      assert taintedObjects.get(key) != null
    }
    assert taintedObjects.get(target['Hello']) != null
  }

  void 'test taint deeply char sequence'() {
    given:
    final target = stringBuilder('taint me')

    when:
    module.taintDeeply(target, SourceTypes.GRPC_BODY, { true })

    then:
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
    final maxSize = Config.get().iastTruncationMaxValueLength

    when:
    module.taint(target, SourceTypes.REQUEST_PARAMETER_VALUE)

    then:
    final tainted = taintedObjects.get(target)
    tainted != null
    final sourceValue  = tainted.ranges.first().source.value
    sourceValue.length() <= target.length()
    sourceValue.length() <= maxSize

    where:
    target                                                                          | _
    string((0..Config.get().getIastTruncationMaxValueLength() * 2).join(''))        | _
    stringBuilder((0..Config.get().getIastTruncationMaxValueLength() * 2).join('')) | _
  }

  void 'test that source names should not make a strong reference over the value'() {
    given:
    final name = 'name'

    when:
    module.taint(name, SourceTypes.REQUEST_PARAMETER_NAME, name)

    then:
    final tainted = taintedObjects.get(name)
    final taintedName = tainted.ranges[0].source.name
    assert !taintedName.is(name) : 'Weak value should not be retained by the source name'
  }

  private List<Tuple<Object>> taintIfSuite() {
    return [
      Tuple.tuple(string('string'), string('string')),
      Tuple.tuple(string('string'), stringBuilder('stringBuilder')),
      Tuple.tuple(string('string'), date()),
      Tuple.tuple(string('string'), taintable()),
      Tuple.tuple(stringBuilder('stringBuilder'), string('string')),
      Tuple.tuple(stringBuilder('stringBuilder'), stringBuilder('stringBuilder')),
      Tuple.tuple(stringBuilder('stringBuilder'), date()),
      Tuple.tuple(stringBuilder('stringBuilder'), taintable()),
      Tuple.tuple(date(), string('string')),
      Tuple.tuple(date(), stringBuilder('stringBuilder')),
      Tuple.tuple(date(), date()),
      Tuple.tuple(date(), taintable()),
      Tuple.tuple(taintable(), string('string')),
      Tuple.tuple(taintable(), stringBuilder('stringBuilder')),
      Tuple.tuple(taintable(), date()),
      Tuple.tuple(taintable(), taintable())
    ]
  }

  private TaintedObject getTaintedObject(final Object target) {
    if (target instanceof Taintable) {
      final source = (target as Taintable).$$DD$getSource() as Source
      return source == null ? null : new TaintedObject(target, Ranges.forObject(source), null)
    }
    return taintedObjects.get(target)
  }

  private TaintedObject taintObject(final Object target, Source source, int mark = NOT_MARKED) {
    if (target instanceof Taintable) {
      target.$$DD$setSource(source)
    } else if (target instanceof CharSequence) {
      taintedObjects.taint(target, Ranges.forCharSequence(target, source, mark))
    } else {
      taintedObjects.taint(target, Ranges.forObject(source, mark))
    }
    return getTaintedObject(target)
  }

  private TaintedObject taintObject(final Object target, Range[] ranges) {
    if (target instanceof Taintable) {
      target.$$DD$setSource(ranges[0].getSource())
    } else {
      taintedObjects.taint(target, ranges)
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
    final result = new Date()
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
    final originalValue = tainted.get()
    assert tainted.ranges.length == ranges.length
    ranges.eachWithIndex { Range expected, int i ->
      final range = tainted.ranges[i]
      if (mark == NOT_MARKED) {
        assert range.marks == expected.marks
      } else {
        assert (range.marks & mark) > 0
      }
      final source = range.source
      assert !source.name.is(originalValue): 'Weak value should not be retained by the source name'
      assert !source.value.is(originalValue): 'Weak value should not be retained by the source value'

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
