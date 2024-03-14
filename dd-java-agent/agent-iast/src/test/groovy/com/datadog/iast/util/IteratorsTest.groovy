package com.datadog.iast.util

import spock.lang.Specification

class IteratorsTest extends Specification {

  void 'headed array iterator'() {
    when:
    final iterator = Iterators.of(head, tail as String[])

    then:
    (0..<expected.size()).each {
      assert iterator.hasNext()
      final next = iterator.next()
      final expectedNext = expected.remove(0)
      assert next == expectedNext
    }

    when:
    iterator.next()

    then:
    thrown(NoSuchElementException)

    where:
    head    | tail           | expected
    'hello' | null           | ['hello']
    'hello' | []             | ['hello']
    'hello' | ['World!']     | ['hello', 'World!']
    'hello' | ['World', '!'] | ['hello', 'World', '!']
  }

  void 'array iterator'() {
    when:
    final iterator = Iterators.of(array as String[])

    then:
    if (!expected.empty) {
      (0..<expected.size()).each {
        assert iterator.hasNext()
        final next = iterator.next()
        final expectedNext = expected.remove(0)
        assert next == expectedNext
      }
    } else {
      !iterator.hasNext()
    }

    when:
    iterator.next()

    then:
    thrown(NoSuchElementException)

    where:
    array                   | expected
    null                    | []
    []                      | []
    ['hello']               | ['hello']
    ['hello', 'World', '!'] | ['hello', 'World', '!']
  }

  void 'joined iterator'() {
    when:
    final iterator = Iterators.join(iterators as Iterator<?>[])

    then:
    if (!expected.empty) {
      (0..<expected.size()).each {
        assert iterator.hasNext()
        final next = iterator.next()
        final expectedNext = expected.remove(0)
        assert next == expectedNext
      }
    } else {
      !iterator.hasNext()
    }

    when:
    iterator.next()

    then:
    thrown(NoSuchElementException)

    where:
    iterators                                                | expected
    [['1', '2', '3'].iterator()]                             | ['1', '2', '3']
    [['1', '2', '3'].iterator(), [].iterator()]              | ['1', '2', '3']
    [['1', '2', '3'].iterator(), ['4', '5', '6'].iterator()] | ['1', '2', '3', '4', '5', '6']
  }
}
