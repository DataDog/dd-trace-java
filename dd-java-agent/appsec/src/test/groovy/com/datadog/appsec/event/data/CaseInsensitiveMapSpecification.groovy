package com.datadog.appsec.event.data

import spock.lang.Specification

class CaseInsensitiveMapSpecification extends Specification {
  CaseInsensitiveMap<String> map

  void 'get and containsKey'() {
    map = new CaseInsensitiveMap<>(['FOO': 'bar'])

    expect:
    map.containsKey('fOo')
    map['fOo'] == 'bar'
  }

  void size() {
    when:
    map = new CaseInsensitiveMap<>()

    then:
    assert map.isEmpty()
    map.size() == 0

    when:
    map = new CaseInsensitiveMap<>([a: 'b'])

    then:
    assert !map.isEmpty()
    map.size() == 1
  }

  void containsValue() {
    when:
    map = new CaseInsensitiveMap<>([a: 'b'])

    then:
    assert map.containsValue('b')
    assert !map.containsValue('c')
  }

  void 'put and putAll'() {
    map = new CaseInsensitiveMap<>()

    when:
    map.put('A', 'b')

    then:
    assert 'a' in map

    when:
    map.putAll([B: 'x', C: 'y'])

    then:
    assert 'b' in map
    assert 'c' in map
  }

  void 'clear and remove'() {
    when:
    map = new CaseInsensitiveMap<>([A: 'a'])
    map.clear()

    then:
    map.size() == 0

    when:
    map = new CaseInsensitiveMap<>([A: 'b'])
    map.remove 'a'

    then:
    map.size() == 0
  }

  void 'keySet values and entrySet'() {
    when:
    map = new CaseInsensitiveMap<>(['A': 'B'])

    then:
    map.values().size() == 1
    map.values().first() == 'B'
    map.keySet() == ['a'] as Set
    def entrySet = map.entrySet()
    entrySet.size() == 1
    def entry = entrySet.first()
    entry.key == 'a'
    entry.value == 'B'
  }
}
