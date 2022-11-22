package com.datadog.iast.taint

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.test.util.DDSpecification

import static com.datadog.iast.taint.Ranges.rangesProviderFor

class RangesTest extends DDSpecification {

  def 'forString'() {
    given:
    final source = new Source(SourceType.NONE, null, null)

    when:
    final result = Ranges.forString(s, source)

    then:
    result != null
    result.length == 1
    result[0].start == 0
    result[0].length == s.length()
    result[0].source == source

    where:
    s    | _
    ""   | _
    "x"  | _
    "xx" | _
  }

  def 'copyShift'() {
    given:
    def src = rangesFromSpec(srcSpec)
    def dst = new Range[dstLen] as Range[]
    def exp = rangesFromSpec(expSpec)

    when:
    Ranges.copyShift(src, dst, dstPos, shift)

    then:
    dst == exp

    where:
    dstPos | dstLen | shift | srcSpec  | expSpec
    0      | 0      | 0     | []       | []
    0      | 0      | 1     | []       | []
    0      | 0      | -1    | []       | []
    0      | 1      | 0     | []       | [null]
    0      | 1      | 1     | []       | [null]
    0      | 1      | -1    | []       | [null]
    0      | 1      | 0     | [[1, 1]] | [[1, 1]]
    0      | 1      | 1     | [[1, 1]] | [[2, 1]]
    0      | 1      | -1    | [[1, 1]] | [[0, 1]]
    0      | 2      | 0     | [[1, 1]] | [[1, 1], null]
    0      | 2      | 1     | [[1, 1]] | [[2, 1], null]
    0      | 2      | -1    | [[1, 1]] | [[0, 1], null]
    1      | 2      | 0     | [[1, 1]] | [null, [1, 1]]
    1      | 2      | 1     | [[1, 1]] | [null, [2, 1]]
    1      | 2      | -1    | [[1, 1]] | [null, [0, 1]]
  }

  void 'test range provider'(final Object values, final List<TaintedObject> tainted, final int size, final int rangeCount) {
    setup:
    final to = Mock(TaintedObjects)
    values.eachWithIndex { Object entry, int i ->
      to.get(entry) >> tainted.get(i)
    }

    when:
    final provider = rangesProviderFor(to, values)

    then:
    provider.size() == size
    provider.rangeCount() == rangeCount
    values.eachWithIndex { Object entry, int i ->
      final value = provider.value(i)
      assert value == entry
      assert provider.ranges(value) == tainted.get(i)?.getRanges()
    } == values

    where:
    values                                | tainted                                                 | size | rangeCount
    ['a', 'b', 'c', 'd']                  | [null, ranged(2), null, ranged(6), null]                | 4    | 8
    ['a', 'b', 'c', 'd', 'e'] as String[] | [ranged(1), ranged(1), ranged(1), ranged(1), ranged(1)] | 5    | 5
  }

  void 'test empty range provider'() {
    setup:
    final to = Mock(TaintedObjects)
    final provider = rangesProviderFor(to, [])

    when:
    final rangeCount = provider.rangeCount()
    final size = provider.size()

    then:
    rangeCount == 0
    size == 0

    when:
    provider.value(0)

    then:
    thrown(UnsupportedOperationException)

    when:
    provider.ranges('abc')

    then:
    thrown(UnsupportedOperationException)
  }

  Range[] rangesFromSpec(List<List<Object>> spec) {
    def ranges = new Range[spec.size()]
    int j = 0
    for (int i = 0; i < spec.size(); i++) {
      if (spec[i] == null) {
        continue
      }
      ranges[i] = new Range(
        spec[i][0] as int,
        spec[i][1] as int,
        new Source(SourceType.NONE, String.valueOf(j), null))
      j++
    }
    ranges
  }

  TaintedObject ranged(final int rangeCount) {
    final Range[] ranges = new Range[rangeCount]
    return Mock(TaintedObject) {
      getRanges() >> ranges
    }
  }
}
