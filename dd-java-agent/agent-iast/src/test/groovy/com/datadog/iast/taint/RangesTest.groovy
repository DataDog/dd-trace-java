package com.datadog.iast.taint

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.Config
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.test.util.DDSpecification

import static com.datadog.iast.util.HttpHeader.LOCATION
import static com.datadog.iast.util.HttpHeader.REFERER
import static datadog.trace.api.iast.SourceTypes.GRPC_BODY
import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_VALUE
import static datadog.trace.api.iast.SourceTypes.REQUEST_QUERY
import static datadog.trace.api.iast.SourceTypes.SQL_TABLE
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED
import static com.datadog.iast.taint.Ranges.mergeRanges
import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_NAME

class RangesTest extends DDSpecification {

  private static final int NEGATIVE_MARK = 1 << 31
  private static final int MAX_RANGE_COUNT = Config.get().iastMaxRangeCount

  void 'forString'() {
    given:
    final source = new Source(SourceTypes.NONE, null, null)

    when:
    final result = Ranges.forCharSequence(s, source, VulnerabilityMarks.SQL_INJECTION_MARK)

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

  void 'getNotMarkedRanges'(final int mark) {
    given:

    final range1 = new Range(0, 1, null, NOT_MARKED)
    final range2 = new Range(0, 1, null, mark)
    final range3 = new Range(0, 1, null, NOT_MARKED)
    final range4 = new Range(0, 1, null, mark)
    final Range[] allNotMarked = [range1, range3]
    final Range[] notAllMarked = [range1, range2, range3, range4]
    final Range[] allMarked = [range2, range4]
    final Range[] empty = new Range[0]

    when:
    Range[] result = Ranges.getNotMarkedRanges(null, NOT_MARKED)

    then:
    result == null

    when:
    result = Ranges.getNotMarkedRanges(empty, NOT_MARKED)

    then:
    result == empty

    when:
    result = Ranges.getNotMarkedRanges(allMarked, mark)

    then:
    result == null

    when:
    result = Ranges.getNotMarkedRanges(allNotMarked, NOT_MARKED)

    then:
    result == allNotMarked

    when:
    result = Ranges.getNotMarkedRanges(notAllMarked, mark)

    then:
    result.length == 2
    result[0] == range1
    result[1] == range3

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

  void 'test merge ranges with limits'() {
    given:
    final leftRanges = (0..<left).collect { index -> rangeFor(index) } as Range[]
    final rightRanges = (0..<right).collect { index -> rangeFor(index) } as Range[]

    when:
    final merged = mergeRanges(offset, leftRanges, rightRanges)

    then:
    merged.size() == expected

    where:
    offset | left            | right           | expected
    0      | 1               | 1               | 2
    0      | MAX_RANGE_COUNT | 1               | MAX_RANGE_COUNT
    0      | 1               | MAX_RANGE_COUNT | MAX_RANGE_COUNT
    0      | MAX_RANGE_COUNT | MAX_RANGE_COUNT | MAX_RANGE_COUNT
    10     | 1               | 1               | 2
    10     | MAX_RANGE_COUNT | 1               | MAX_RANGE_COUNT
    10     | 1               | MAX_RANGE_COUNT | MAX_RANGE_COUNT
    10     | MAX_RANGE_COUNT | MAX_RANGE_COUNT | MAX_RANGE_COUNT
  }

  void 'test all ranges coming from header'() {
    when:
    final allRangesFrom = Ranges.allRangesFromHeader(header, ranges as Range[])

    then:
    allRangesFrom == expected

    where:
    header  | ranges                                                                           | expected
    REFERER | []                                                                               | true
    REFERER | [rangeWithSource(REQUEST_HEADER_VALUE, header.name)]                             | true
    REFERER | [rangeWithSource(REQUEST_HEADER_VALUE, LOCATION.name)]                           | false
    REFERER | [rangeWithSource(GRPC_BODY)]                                                     | false
    REFERER | [rangeWithSource(REQUEST_HEADER_VALUE, header.name), rangeWithSource(GRPC_BODY)] | false
    REFERER | [
      rangeWithSource(REQUEST_HEADER_VALUE, header.name),
      rangeWithSource(REQUEST_HEADER_VALUE, LOCATION.name)
    ]                                                                                          | false
  }

  void 'test all ranges coming from any header'() {
    when:
    final allRangesFrom = Ranges.allRangesFromAnyHeader(ranges as Range[])

    then:
    allRangesFrom == expected

    where:
    headers   | ranges                                                                            | expected
    [REFERER] | []                                                                                | true
    [REFERER] | [rangeWithSource(REQUEST_HEADER_VALUE, REFERER.name)]                             | true
    [REFERER] | [rangeWithSource(REQUEST_HEADER_VALUE, LOCATION.name)]                            | true
    [REFERER] | [rangeWithSource(GRPC_BODY)]                                                      | false
    [REFERER] | [rangeWithSource(REQUEST_HEADER_VALUE, REFERER.name), rangeWithSource(GRPC_BODY)] | false
    [REFERER] | [
      rangeWithSource(REQUEST_HEADER_VALUE, REFERER.name),
      rangeWithSource(REQUEST_HEADER_VALUE, LOCATION.name)
    ]                                                                                             | true
  }

  void 'test intersection of ranges'() {
    when:
    final intersection = Ranges.intersection(target, ranges as Range[])

    then:
    final list = intersection == null ? [] : intersection.toList()
    list.size() == expected.size()
    list.containsAll(expected)

    where:
    target      | ranges        | expected
    range(2, 4) | [range(0, 2)] | [] // [2, 3, 4, 5] | [0, 1] -> []
    range(2, 4) | [range(0, 4)] | [range(2, 2)] // [2, 3, 4, 5] | [0, 1, 2, 3] -> [2, 3]
    range(2, 4) | [range(2, 4)] | [range(2, 4)] // [2, 3, 4, 5] | [2, 3, 4, 5] -> [2, 3, 4, 5]
    range(2, 4) | [range(3, 1)] | [range(3, 1)] // [2, 3, 4, 5] | [3] -> [3]
    range(2, 4) | [range(4, 4)] | [range(4, 2)] // [2, 3, 4, 5] | [4, 5, 6, 7] -> [4, 5]
    range(2, 4) | [range(6, 4)] | [] // [2, 3, 4, 5] | [6, 7, 8, 9] -> []
  }

  void 'test forSubstring'() {
    when:
    def result = Ranges.forSubstring(offset, length, srcSpec as Range[])

    then:
    final list = result == null ? [] : result.toList()
    list.size() == expected.size()
    list.containsAll(expected)

    where:
    offset | length | srcSpec                                  | expected
    1      | 7      | [range(4, 3)]                            | [range(3, 3)]
    0      | 4      | [range(4, 3)]                            | []
    7      | 2      | [range(4, 3)]                            | []
    1      | 4      | [range(4, 3)]                            | [range(3, 1)]
    1      | 5      | [range(4, 3)]                            | [range(3, 2)]
    4      | 3      | [range(4, 3)]                            | [range(0, 3)]
    6      | 2      | [range(4, 3)]                            | [range(0, 1)]
    5      | 3      | [range(4, 3)]                            | [range(0, 2)]
    4      | 2      | [range(4, 3)]                            | [range(0, 2)]
    1      | 9      | [range(2, 3), range(6, 3), range(15, 1)] | [range(1, 3), range(5, 3)]
    1      | 1      | [range(2, 3), range(6, 3), range(15, 1)] | []
    5      | 1      | [range(2, 3), range(6, 3), range(15, 1)] | []
    9      | 1      | [range(2, 3), range(6, 3), range(15, 1)] | []
    1      | 3      | [range(2, 3), range(6, 3), range(15, 1)] | [range(1, 2)]
    2      | 2      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 2)]
    2      | 3      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 3)]
    1      | 7      | [range(2, 3), range(6, 3), range(15, 1)] | [range(1, 3), range(5, 2)]
    2      | 6      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 3), range(4, 2)]
    2      | 7      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 3), range(4, 3)]
    5      | 3      | [range(2, 3), range(6, 3), range(15, 1)] | [range(1, 2)]
    6      | 2      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 2)]
    6      | 3      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 3)]
    4      | 5      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 1), range(2, 3)]
    4      | 4      | [range(2, 3), range(6, 3), range(15, 1)] | [range(0, 1), range(2, 2)]
  }

  void 'merge ranges keeping order'() {
    when:
    final result = Ranges.mergeRangesSorted(left as Range[], right as Range[])

    then:
    final expectedArray = expected as Range[]
    result == expectedArray

    where:
    left                       | right                      | expected
    []                         | []                         | []
    []                         | [range(2, 2)]              | [range(2, 2)]
    [range(2, 2)]              | []                         | [range(2, 2)]
    [range(2, 2)]              | [range(2, 2)]              | [range(2, 2), range(2, 2)]
    [range(0, 2)]              | [range(2, 2)]              | [range(0, 2), range(2, 2)]
    [range(4, 2)]              | [range(2, 2)]              | [range(2, 2), range(4, 2)]
    [range(0, 6)]              | [range(2, 2)]              | [range(0, 6), range(2, 2)]
    [range(0, 2), range(4, 2)] | [range(2, 2)]              | [range(0, 2), range(2, 2), range(4, 2)]
    [range(2, 2), range(6, 2)] | [range(0, 2), range(4, 2)] | [range(0, 2), range(2, 2), range(4, 2), range(6, 2)]
  }

  void 'test forIndentation method'() {
    when:
    final result = Ranges.forIndentation(input, indentation, ranges as Range[])

    then:
    final expectedArray = expected as Range[]
    result == expectedArray

    where:
    input                | indentation | ranges                                                                                            | expected
    "123\n123"           | 4           | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(6, 1, (byte) 2, null, "3")]        | [rangeWithSource(4, 3, (byte) 1, null, "123"), rangeWithSource(14, 1, (byte) 2, null, "3")]
    "123\r\n123"         | 4           | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(7, 1, (byte) 2, null, "3")]        | [rangeWithSource(4, 3, (byte) 1, null, "123"), rangeWithSource(14, 1, (byte) 2, null, "3")]
    "123\n123"           | 4           | [rangeWithSource(0, 5, (byte) 1, null, "123\n1"), rangeWithSource(6, 1, (byte) 2, null, "3")]     | [rangeWithSource(4, 9, (byte) 1, null, "123\n1"), rangeWithSource(14, 1, (byte) 2, null, "3")]
    "123\r\n123"         | 4           | [rangeWithSource(0, 6, (byte) 1, null, "123\r\n1"), rangeWithSource(7, 1, (byte) 2, null, "3")]   | [rangeWithSource(4, 9, (byte) 1, null, "123\r\n1"), rangeWithSource(14, 1, (byte) 2, null, "3")]
    "123\n123"           | 0           | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(6, 1, (byte) 2, null, "3")]        | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(6, 1, (byte) 2, null, "3")]
    "123\r\n123"         | 0           | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(7, 1, (byte) 2, null, "3")]        | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(6, 1, (byte) 2, null, "3")]
    "123\n123"           | 0           | [rangeWithSource(0, 5, (byte) 1, null, "123\n1"), rangeWithSource(6, 1, (byte) 2, null, "3")]     | [rangeWithSource(0, 5, (byte) 1, null, "123\n1"), rangeWithSource(6, 1, (byte) 2, null, "3")]
    "123\r\n123"         | 0           | [rangeWithSource(0, 6, (byte) 1, null, "123\r\n1"), rangeWithSource(7, 1, (byte) 2, null, "3")]   | [rangeWithSource(0, 5, (byte) 1, null, "123\r\n1"), rangeWithSource(6, 1, (byte) 2, null, "3")]
    "    123\n    123"   | -4          | [rangeWithSource(4, 3, (byte) 1, null, "123"), rangeWithSource(14, 1, (byte) 2, null, "3")]       | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(6, 1, (byte) 2, null, "3")]
    "    123\r\n    123" | -4          | [rangeWithSource(4, 3, (byte) 1, null, "123"), rangeWithSource(15, 1, (byte) 2, null, "3")]       | [rangeWithSource(0, 3, (byte) 1, null, "123"), rangeWithSource(6, 1, (byte) 2, null, "3")]
    "    123\n    123"   | -4          | [rangeWithSource(4, 9, (byte) 1, null, "123\n1"), rangeWithSource(14, 1, (byte) 2, null, "3")]    | [rangeWithSource(0, 5, (byte) 1, null, "123\n1"), rangeWithSource(6, 1, (byte) 2, null, "3")]
    "    123\r\n    123" | -4          | [rangeWithSource(4, 10, (byte) 1, null, "123\r\n1"), rangeWithSource(15, 1, (byte) 2, null, "3")] | [rangeWithSource(0, 5, (byte) 1, null, "123\r\n1"), rangeWithSource(6, 1, (byte) 2, null, "3")]
  }

  void 'test splitRanges method'() {
    when:
    final result = Ranges.splitRanges(start, end, newLength, range as Range, offset, diffLength)

    then:
    final expectedArray = expected as Range[]
    result == expectedArray

    where:
    start | end | newLength | range       | offset | diffLength | expected
    1     | 3   | 2         | range(0, 8) | 0      | 0          | [range(0, 1), range(3, 5)]
    1     | 3   | 2         | range(0, 8) | 2      | 0          | [range(2, 1), range(5, 5)]
    2     | 4   | 2         | range(1, 8) | -1     | 0          | [range(0, 1), range(3, 5)]
    1     | 3   | 3         | range(0, 8) | 0      | -1         | [range(0, 1), range(4, 3)]
    1     | 3   | 3         | range(0, 8) | 2      | -1         | [range(2, 1), range(6, 3)]
    2     | 3   | 2         | range(1, 8) | -1     | -1         | [range(0, 1), range(3, 4)]
    1     | 3   | 3         | range(0, 8) | 0      | 1          | [range(0, 1), range(4, 5)]
    1     | 3   | 3         | range(0, 8) | 2      | 1          | [range(2, 1), range(6, 5)]
    2     | 3   | 2         | range(1, 8) | -1     | 1          | [range(0, 1), range(3, 6)]
    8     | 10  | 2         | range(0, 8) | 0      | 0          | [range(0, 8)]
    1     | 3   | 2         | range(8, 8) | 0      | 0          | []
  }

  void 'test excludeRangesBySource method'() {
    when:
    final result = Ranges.excludeRangesBySource(ranges as Range[], source as BitSet)

    then:
    final expectedArray = expected as Range[]
    result == expectedArray

    where:
    ranges                                          | source                                       | expected
    [rangeWithSource(0, 5, SQL_TABLE), range(5, 3)] | bitSetOf(SQL_TABLE)                          | [range(5, 3)]
    [rangeWithSource(0, 5, SQL_TABLE), range(5, 3)] | bitSetOf(SQL_TABLE, REQUEST_QUERY)           | [range(5, 3)]
    [rangeWithSource(0, 5, SQL_TABLE), range(5, 3)] | bitSetOf(REQUEST_HEADER_NAME)                | [rangeWithSource(0, 5, SQL_TABLE)]
    [rangeWithSource(0, 5, SQL_TABLE), range(5, 3)] | bitSetOf(REQUEST_QUERY)                      | [rangeWithSource(0, 5, SQL_TABLE), range(5, 3)]
    [rangeWithSource(0, 5, SQL_TABLE), range(5, 3)] | bitSetOf(REQUEST_QUERY, REQUEST_HEADER_NAME) | [rangeWithSource(0, 5, SQL_TABLE)]
    []                                              | bitSetOf(SQL_TABLE)                          | []
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

  Range rangeFor(final int index) {
    return new Range(index, 1, new Source(REQUEST_HEADER_NAME, 'a', 'b'), NOT_MARKED)
  }

  Range rangeWithSource(final byte source, final String name = 'name', final String value = 'value') {
    return new Range(0, 10, new Source(source, name, value), NOT_MARKED)
  }

  Range range(final int start, final int length) {
    return new Range(start, length, new Source(REQUEST_HEADER_NAME, 'a', 'b'), NOT_MARKED)
  }

  Range rangeWithSource(final int start, final int length, final byte source, final String name = 'name', final String value = 'value') {
    return new Range(start, length, new Source(source, name, value), NOT_MARKED)
  }

  BitSet bitSetOf(byte... values) {
    BitSet bitSet = new BitSet()
    for (byte value : values) {
      bitSet.set(value)
    }
    return bitSet
  }
}
