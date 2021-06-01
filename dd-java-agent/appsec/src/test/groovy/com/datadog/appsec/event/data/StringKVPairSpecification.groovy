package com.datadog.appsec.event.data

import spock.lang.Specification

class StringKVPairSpecification extends Specification {

  void 'retrieval methods'() {
    def pair = new StringKVPair('a', 'b')

    expect:
    pair.getKey() == 'a'
    pair.getValue() == 'b'

    assert pair == new StringKVPair('a', 'b')
    pair != new StringKVPair('b', 'b')
    pair != new StringKVPair('a', 'a')

    // bypass has special logic for == and Lists
    def equalsMethod = StringKVPair.getClass().getMethod('equals', Object)
    equalsMethod.invoke(pair, new StringKVPair('a', 'b')) == true
    equalsMethod.invoke(pair, new StringKVPair('b', 'b')) == false
    equalsMethod.invoke(pair, new StringKVPair('a', 'a')) == false


    pair.hashCode() == new StringKVPair('a', 'b').hashCode()
  }

  void 'list retrieval methods'() {
    def pair = new StringKVPair('a', 'b')

    expect:
    pair[0] == 'a'
    pair[1] == 'b'
    pair.size() == 2
    assert !pair.empty
    assert 'a' in pair
    assert 'b' in pair
    pair.toArray() == ['a', 'b'] as String[]
    pair.toArray(new String[2]) == ['a', 'b'] as String[]
    pair.toArray(new String[0]) == ['a', 'b'] as String[]
    assert pair.containsAll(['a', 'b'])
    assert !pair.containsAll(['a', 'b', 'c'])
    pair.indexOf('a') == 0
    pair.indexOf('b') == 1
    pair.indexOf('c') == -1
    pair.lastIndexOf('a') == 0
    pair.lastIndexOf('b') == 1
    pair.lastIndexOf('c') == -1
    pair.subList(1, 2) == ['b']
  }

  void iterators() {
    def elem
    def pair = new StringKVPair('a', 'b')
    def iterator

    when:
    iterator = pair.listIterator(0)

    then:
    iterator.size() == 2

    when:
    iterator = pair.listIterator(0)

    then:
    assert iterator.hasNext()
    assert !iterator.hasPrevious()
    iterator.nextIndex() == 0
    iterator.previousIndex() == -1

    when:
    elem = iterator.next()

    then:
    assert iterator.hasNext()
    assert iterator.hasPrevious()
    iterator.nextIndex() == 1
    iterator.previousIndex() == 0
    elem == 'a'

    when:
    elem = iterator.next()

    then:
    assert !iterator.hasNext()
    assert iterator.hasPrevious()
    iterator.nextIndex() == 2
    iterator.previousIndex() == 1
    elem == 'b'

    when:
    iterator.next()

    then:
    thrown(NoSuchElementException)

    when:
    elem = iterator.previous()

    then:
    assert iterator.hasNext()
    assert iterator.hasPrevious()
    iterator.nextIndex() == 1
    iterator.previousIndex() == 0
    elem == 'a'

    when:
    iterator.previous()

    then:
    thrown(NoSuchElementException)
  }

  void 'null values are converted to empty strings'() {
    when:
    def pair = new StringKVPair(null, null)

    then:
    pair.key == ''
    pair.value == ''
  }

  void 'out of bounds tests'() {
    def pair = new StringKVPair('a', 'b')

    when:
    pair.get(2)

    then:
    thrown(IndexOutOfBoundsException)

    when:
    pair.subList(2, 3)

    then:
    thrown(IndexOutOfBoundsException)

    when:
    pair.listIterator(3)

    then:
    thrown(IndexOutOfBoundsException)
  }

  void 'unimplemented iterator methods'() {
    when:
    def pair = new StringKVPair('a', 'b')
    def iterator = pair.listIterator()
    method(iterator)

    then:
    thrown(UnsupportedOperationException)

    where:
    method << [
      { it.remove() },
      { it.set('foo') },
      { it.add('foo') },
    ]
  }

  void 'unimplemented methods'() {
    def pair = new StringKVPair('a', 'b')

    when:
    method(pair)

    then:
    thrown(UnsupportedOperationException)

    where:
    method << [
      { it.add 'foo' },
      { it.remove 'foo' },
      { it.addAll(['foo']) },
      { it.addAll(0, ['foo']) },
      { it.removeAll(['foo']) },
      { it.retainAll(['foo']) },
      { it.clear() },
      { it.set(0, 'foo') },
      { it.add(0, 'foo') },
      { it.remove(0) },
    ]
  }
}
