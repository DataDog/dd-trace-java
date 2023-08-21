package com.datadog.iast.util

import spock.lang.Shared
import spock.lang.Specification

class RangedTest extends Specification {

  void 'test intersect operation'() {
    given:
    final rangeA = ranged(a)
    final rangeB = ranged(b)

    when:
    final intersects = rangeA.intersects(rangeB)

    then:
    assert intersects == expected: "'${toString(a)}' ${expected ? 'should' : 'should not'} intersect '${toString(b)}'"

    where:
    a    | b     | expected
    4..8 | 0..3  | false
    4..8 | 0..4  | true
    4..8 | 0..6  | true
    4..8 | 0..8  | true
    4..8 | 0..10 | true
    4..8 | 5..6  | true
    4..8 | 6..10 | true
    4..8 | 9..10 | false
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
    return Ranged.build(range.fromInt, range.toInt - range.fromInt + (range.inclusive ? 1 : 0))
  }

  private static IntRange intRange(final Ranged range) {
    return new IntRange(false, range.start, range.start + range.length)
  }
}
