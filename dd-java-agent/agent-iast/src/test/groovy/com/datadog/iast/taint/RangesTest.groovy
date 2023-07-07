package com.datadog.iast.taint

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.VulnerabilityMarks
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.SourceTypes
import datadog.trace.test.util.DDSpecification

import static com.datadog.iast.model.Range.NOT_MARKED
import static com.datadog.iast.taint.Ranges.areAllMarked
import static com.datadog.iast.taint.Ranges.rangesProviderFor

class RangesTest extends DDSpecification {

  private static final int NEGATIVE_MARK = 1 << 31

  void 'forString'() {
    given:
    final source = new Source(SourceTypes.NONE, null, null)

    when:
    final result = Ranges.forString(s, source, VulnerabilityMarks.SQL_INJECTION_MARK)

    then:
    result != null
    result.length == 1
    result[0].start == 0
    result[0].length == s.length()
    result[0].source == source
    result[0].marks == VulnerabilityMarks.SQL_INJECTION_MARK

    where:
    s    | _
    ""   | _
    "x"  | _
    "xx" | _
  }

  void 'copyShift'() {
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
    null                                  | []                                                      | 0    | 0
    []                                    | []                                                      | 0    | 0
    ['a', 'b', 'c', 'd']                  | [null, ranged(2), null, ranged(6), null]                | 4    | 8
    ['a', 'b', 'c', 'd', 'e'] as String[] | [ranged(1), ranged(1), ranged(1), ranged(1), ranged(1)] | 5    | 5
  }

  void 'test empty range provider'() {
    setup:
    final to = Mock(TaintedObjects)
    final provider = rangesProviderFor(to, items)

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

    where:
    items | _
    null  | _
    []    | _
  }

  void 'getIncludedRangesInterval'() {
    given:
    def src = rangesFromSpec(srcSpec)

    when:
    def result = Ranges.getIncludedRangesInterval(offset, length, src)

    then:
    result == expected

    where:
    offset | length | srcSpec                   | expected
    1      | 7      | [[4, 3]]                  | [0, -1]
    0      | 4      | [[4, 3]]                  | [-1, -1]
    7      | 2      | [[4, 3]]                  | [-1, -1]
    1      | 4      | [[4, 3]]                  | [0, -1]
    1      | 5      | [[4, 3]]                  | [0, -1]
    4      | 3      | [[4, 3]]                  | [0, -1]
    6      | 2      | [[4, 3]]                  | [0, -1]
    5      | 3      | [[4, 3]]                  | [0, -1]
    4      | 2      | [[4, 3]]                  | [0, -1]
    1      | 9      | [[2, 3], [6, 3], [15, 1]] | [0, 2]
    1      | 1      | [[2, 3], [6, 3], [15, 1]] | [-1, -1]
    5      | 1      | [[2, 3], [6, 3], [15, 1]] | [-1, -1]
    9      | 1      | [[2, 3], [6, 3], [15, 1]] | [-1, -1]
    1      | 3      | [[2, 3], [6, 3], [15, 1]] | [0, 1]
    2      | 2      | [[2, 3], [6, 3], [15, 1]] | [0, 1]
    2      | 3      | [[2, 3], [6, 3], [15, 1]] | [0, 1]
    1      | 7      | [[2, 3], [6, 3], [15, 1]] | [0, 2]
    2      | 6      | [[2, 3], [6, 3], [15, 1]] | [0, 2]
    2      | 7      | [[2, 3], [6, 3], [15, 1]] | [0, 2]
    5      | 3      | [[2, 3], [6, 3], [15, 1]] | [1, 2]
    6      | 2      | [[2, 3], [6, 3], [15, 1]] | [1, 2]
    6      | 3      | [[2, 3], [6, 3], [15, 1]] | [1, 2]
    4      | 5      | [[2, 3], [6, 3], [15, 1]] | [0, 2]
    4      | 4      | [[2, 3], [6, 3], [15, 1]] | [0, 2]
  }

  void 'forObject'() {
    given:
    final source = new Source(SourceTypes.NONE, null, null)

    when:
    final result = Ranges.forObject(source, VulnerabilityMarks.SQL_INJECTION_MARK)

    then:
    result != null
    result.length == 1
    result[0].start == 0
    result[0].length == Integer.MAX_VALUE
    result[0].source == source
    result[0].marks == VulnerabilityMarks.SQL_INJECTION_MARK
  }

  void 'areAllMarked'(final int mark) {
    given:
    final range1 = new Range(0, 1, null, mark)
    final range2 = new Range(2, 1, null, mark)
    final range3 = new Range(4, 1, null, mark)
    final range4 = new Range(6, 1, null, NOT_MARKED)
    final Range[] noRangesMarked = [range4]
    final Range[] allRangesMarked = [range1, range2, range3]
    final Range[] notAllRangesMarked = [range3, range4]

    when:
    def check = areAllMarked(noRangesMarked, mark)

    then:
    check == false

    when:
    check = areAllMarked(notAllRangesMarked, mark)

    then:
    check == false

    when:
    check = areAllMarked(allRangesMarked, mark)

    then:
    check == true

    where:
    mark                                          | _
    VulnerabilityType.XPATH_INJECTION.mark()      | _
    VulnerabilityType.UNVALIDATED_REDIRECT.mark() | _
    VulnerabilityType.LDAP_INJECTION.mark()       | _
    VulnerabilityType.COMMAND_INJECTION.mark()    | _
    VulnerabilityType.PATH_TRAVERSAL.mark()       | _
    VulnerabilityType.SQL_INJECTION.mark()        | _
    VulnerabilityType.SSRF.mark()                 | _
    NEGATIVE_MARK                                 | _
  }

  void 'areAllMarked false if range array has no elements'() {
    given:
    final Range[] array = []

    when:
    final check = Ranges.areAllMarked(array, NEGATIVE_MARK)

    then:
    check == false
  }

  void 'areAllMarked for multiple vulnerability types'() {
    given:
    final range1 = new Range(0, 1, null, VulnerabilityMarks.SQL_INJECTION_MARK | NEGATIVE_MARK)
    final range2 = new Range(2, 1, null, VulnerabilityMarks.SQL_INJECTION_MARK | NEGATIVE_MARK)
    final range3 = new Range(4, 1, null, VulnerabilityMarks.SQL_INJECTION_MARK)

    final Range[] ranges = [range1, range2, range3]

    when:
    def check = Ranges.areAllMarked(ranges, VulnerabilityMarks.SQL_INJECTION_MARK)

    then:
    check == true

    when:
    check = Ranges.areAllMarked(ranges, NEGATIVE_MARK)

    then:
    check == false


    when:
    check = Ranges.areAllMarked(ranges, VulnerabilityMarks.SSRF_MARK)

    then:
    check == false
  }

  void 'highestPriorityRange'() {
    given:
    final range1 = new Range(0, 1, null, NOT_MARKED)
    final range2 = new Range(0, 1, null, VulnerabilityMarks.SQL_INJECTION_MARK)
    final range3 = new Range(0, 1, null, NOT_MARKED)
    final range4 = new Range(0, 1, null, NEGATIVE_MARK)
    final Range[] allNotMarked = [range1, range3]
    final Range[] notAllMarked = [range1, range2, range3, range4]
    final Range[] allMarked = [range2, range4]

    when:
    def result = Ranges.highestPriorityRange(allNotMarked)

    then:
    result == range1

    when:
    result = Ranges.highestPriorityRange(notAllMarked)

    then:
    result == range2

    when:
    result = Ranges.highestPriorityRange(allMarked)

    then:
    result == range2
  }

  void 'copyWithPosition'() {
    given:
    final source = new Source(SourceTypes.NONE, null, null)
    final range = new Range(0, 1, source, VulnerabilityMarks.SQL_INJECTION_MARK)

    when:
    final result = Ranges.copyWithPosition(range, 2, 4)

    then:
    result != null
    result.start == 2
    result.length == 4
    result.source == source
    result.marks == VulnerabilityMarks.SQL_INJECTION_MARK
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
        new Source(SourceTypes.NONE, String.valueOf(j), null),
        NOT_MARKED)
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
