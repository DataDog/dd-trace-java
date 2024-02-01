package com.datadog.iast.util

import spock.lang.Specification

class HttpHeaderMapTest extends Specification {

  void 'test adding key'() {
    given:
    final map = new HttpHeaderMap<String>()
    final value = 'hello'

    when:
    map.put('oRiGiN', value)

    then:
    map.get('origin') === value
  }

  void 'test update key'() {
    given:
    final map = new HttpHeaderMap<String>()
    final value = 'hello'
    final newValue = 'hello world!'
    map.put('oRiGiN', value)

    when:
    final result = map.put('origin', newValue)

    then:
    result === value
    map.get('OrIgIn') === newValue
  }

  void 'test remove key'() {
    given:
    final map = new HttpHeaderMap<String>()
    final value = 'hello'
    map.put('oRiGiN', value)

    when:
    final result = map.remove('origin')

    then:
    result === value
    map.get('origin') === null
  }

  void 'test with single bucket'() {
    given:
    final map = new HttpHeaderMap<String>(1)
    final items = (1..5).collect { "header-$it" }
    final first = items.first()
    final middle = items[items.size() >> 1]
    final last = items.last()
    def expectedSize = items.size()

    when:
    items.each { map.put(it, it) }

    then:
    items.each { assert map.get(it) === it }
    map.size() == expectedSize

    when:
    final firstRemoved = map.remove(first)
    expectedSize--

    then:
    firstRemoved === first
    map.get(first) === null
    map.size() == expectedSize

    when:
    final middleRemoved = map.remove(middle)
    expectedSize--

    then:
    middleRemoved === middle
    map.get(middle) === null
    map.size() == expectedSize

    when:
    final lastRemoved = map.remove(last)
    expectedSize--

    then:
    lastRemoved === last
    map.get(last) === null
    map.size() == expectedSize
  }
}
