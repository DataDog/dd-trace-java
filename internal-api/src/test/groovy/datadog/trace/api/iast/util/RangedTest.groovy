package datadog.trace.api.iast.util


import spock.lang.Shared
import spock.lang.Specification

class RangedTest extends Specification {

  void 'test intersect operation'() {
    given:
    final rangeA = ranged(a)
    final rangeB = ranged(b)
    final shouldIntersect = intersection != null

    when:
    final result = rangeA.intersection(rangeB)

    then:
    if (intersection == null) {
      result == null
    } else {
      final start = result.start
      final end = result.start + result.length
      start..end == intersection
    }

    when:
    final intersects = rangeA.intersects(rangeB)

    then:
    assert intersects == shouldIntersect: "'${toString(a)}' ${shouldIntersect ? 'should' : 'should not'} intersect '${toString(b)}'"

    where:
    a    | b     | intersection
    4..8 | 0..3  | null
    4..8 | 0..4  | 4..4
    4..8 | 0..6  | 4..6
    4..8 | 0..8  | 4..8
    4..8 | 0..10 | 4..8
    4..8 | 5..6  | 5..6
    4..8 | 6..10 | 6..8
    4..8 | 9..10 | null
  }

  void 'test isBefore operation'() {
    given:
    final rangeA = ranged(a)
    final rangeB = ranged(b)

    when:
    final result = rangeA.isBefore(rangeB)

    then:
    result == before

    where:
    a    | b     | before
    4..8 | 0..3  | false
    4..8 | 6..10 | true
    4..8 | 4..10 | true
  }

  void 'test contains operation'() {
    given:
    final rangeA = ranged(a)
    final rangeB = ranged(b)

    when:
    final intersects = rangeA.contains(rangeB)

    then:
    assert intersects == expected: "'${toString(a)}' ${expected ? 'should' : 'should not'} contain '${toString(b)}'"

    where:
    a    | b     | expected
    4..8 | 0..3  | false
    4..8 | 0..4  | false
    4..8 | 0..6  | false
    4..8 | 0..8  | false
    4..8 | 0..10 | false
    4..8 | 5..6  | true
    4..8 | 6..10 | false
    4..8 | 9..10 | false
  }

  void 'test remove operation'() {
    given:
    final rangeA = ranged(a)
    final rangeB = ranged(b)

    when:
    final result = rangeA.remove(rangeB)

    then:
    final received = result.collect { intRange(it) }
    assert received == expected: "'${toString(a)}' minus '${toString(b)}' should equal to [${expected.collect { "'" + toString(it) + "'" }.join(', ')}]"

    where:
    a    | b     | expected
    4..8 | 0..3  | [4..8]
    4..8 | 0..4  | [5..8]
    4..8 | 0..6  | [7..8]
    4..8 | 0..8  | []
    4..8 | 0..10 | []
    4..8 | 5..6  | [4..4, 7..8]
    4..8 | 6..10 | [4..5]
    4..8 | 9..10 | [4..8]
  }

  @Shared
  private final String characters = ('a'..'z').toList().join('')

  private String toString(final IntRange ranged) {
    return characters.substring(ranged.fromInt, ranged.toInt + (ranged.inclusive ? 1 : 0))
  }

  private static Ranged ranged(final IntRange range) {
    return range == null ? null : Ranged.build(range.fromInt, range.toInt - range.fromInt + (range.inclusive ? 1 : 0))
  }

  private static IntRange intRange(final Ranged range) {
    return range == null ? null : new IntRange(false, range.start, range.start + range.length)
  }
}
