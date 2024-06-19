package com.datadog.appsec.event.data

import spock.lang.Specification

import java.nio.CharBuffer

import static com.datadog.appsec.event.data.ObjectIntrospection.convert

class ObjectIntrospectionSpecification extends Specification {

  void 'strings are preserved'() {
    expect:
    convert('hello') === 'hello'
  }

  void 'boolean are preserved'() {
    expect:
    convert(true) === true
  }

  void 'bytes are preserved'() {
    expect:
    convert(1 as byte) === 1 as byte
  }

  void 'shorts are preserved'() {
    expect:
    convert(1 as short) === 1 as short
  }

  void 'ints are preserved'() {
    expect:
    convert(1) === 1
  }

  void 'longs are preserved'() {
    expect:
    convert(1L) === 1L
  }

  void 'floats are preserved'() {
    expect:
    convert(1.0F) instanceof Float
    convert(1.0F) == Float.valueOf(1.0F)
  }

  void 'doubles are preserved'() {
    expect:
    convert(1.0) === 1.0
  }

  void 'big integers are preserved'() {
    expect:
    convert(1G) === 1G
  }

  void 'big decimals are preserved'() {
    expect:
    convert(1.0G) === 1.0G
  }

  void 'chars are converted to strings'() {
    expect:
    convert('a' as Character) instanceof String
    convert('a' as Character) == 'a'
  }

  static CharBuffer createCharBuffer(String s) {
    def charBuffer = CharBuffer.allocate(s.length())
    charBuffer.put(s)
    charBuffer.position(0)
    charBuffer
  }

  void 'char sequences are converted to strings'() {
    expect:
    convert(createCharBuffer('hello')) == 'hello'
  }

  void 'iterables are converted to lists'() {
    setup:
    def iter = new Iterable() {
      @Delegate Iterable delegate = ['a', 'b']
    }

    expect:
    convert(iter) instanceof List
    convert(iter) == ['a', 'b']
    convert(['a', 'b']) == ['a', 'b']
  }

  void 'maps are converted to hash maps'() {
    setup:
    def map = new Map() {
      @Delegate Map map = [a: 'b']
    }

    expect:
    convert(map) instanceof HashMap
    convert(map) == [a: 'b']
    convert([(6): 'b']) == ['6': 'b']
    convert([(null): 'b']) == ['null': 'b']
    convert([(true): 'b']) == ['true': 'b']
    convert([('a' as Character): 'b']) == ['a': 'b']
    convert([(createCharBuffer('a')): 'b']) == ['a': 'b']
  }

  void 'arrays are converted into lists'() {
    expect:
    convert([6, 'b'] as Object[]) == [6, 'b']
    convert([null, null] as Object[]) == [null, null]
    convert([1, 2] as int[]) == [1 as int, 2 as int]
    convert([1, 2] as byte[]) == [1 as byte, 2 as byte]
  }

  @SuppressWarnings('UnusedPrivateField')
  static class ClassToBeConverted {
    @SuppressWarnings('FieldName')
    private static String X = 'ignored'
    private String a = 'b'
    private List l = [1, 2]
  }

  class ClassToBeConvertedExt extends ClassToBeConverted {
    @SuppressWarnings('UnusedPrivateField')
    private String c = 'd'
  }

  void 'other objects are converted into hash maps'() {
    expect:
    convert(new ClassToBeConverted()) instanceof HashMap
    convert(new ClassToBeConvertedExt()) == [c: 'd', a: 'b', l: [1, 2]]
  }

  class ProtobufLikeClass {
    String c = 'd'
    int memoizedHashCode = 1
    int memoizedSize = 2
  }

  void 'some field names are ignored'() {
    expect:
    convert(new ProtobufLikeClass()) instanceof HashMap
    convert(new ProtobufLikeClass()) == [c: 'd']
  }

  void 'invalid keys are converted to special strings'() {
    expect:
    convert(Collections.singletonMap(new ClassToBeConverted(), 'a')) == ['invalid_key:1': 'a']
    convert([new ClassToBeConverted(): 'a', new ClassToBeConverted(): 'b']) == ['invalid_key:1': 'a', 'invalid_key:2': 'b']
    convert(Collections.singletonMap([1, 2], 'a')) == ['invalid_key:1': 'a']
  }

  void 'max number of elements is honored'() {
    setup:
    def m = [:]
    128.times { m[it] = 'b' }

    expect:
    convert([['a'] * 255])[0].size() == 254 // +2 for the lists
    convert([['a'] * 255 as String[]])[0].size() == 254 // +2 for the lists
    convert(m).size() == 127 // +1 for the map, 2 for each entry (key and value)
  }

  void 'max depth is honored — array version'() {
    setup:
    Object[] objArray = new Object[1]
    def p = objArray
    22.times {p = p[0] = new Object[1]}

    expect:
    int depth = 0
    for (p = convert(objArray); p != null; p = p[0]) {
      depth++
    }
    depth == 21 // after max depth we have nulls
  }

  void 'max depth is honored — list version'() {
    setup:
    def list = []
    def p = list
    22.times {p << []; p = p[0] }

    expect:
    int depth = 0
    for (p = convert(list); p != null; p = p[0]) {
      depth++
    }
    depth == 21 // after max depth we have nulls
  }

  def 'max depth is honored — map version'() {
    setup:
    def map = [:]
    def p = map
    22.times {p['a'] = [:]; p = p['a'] }

    expect:
    int depth = 0
    for (p = convert(map); p != null; p = p['a']) {
      depth++
    }
    depth == 21 // after max depth we have nulls
  }

  void 'conversion of an element throws'() {
    setup:
    def cs = new CharSequence() {
      @Delegate String s = ''

      @Override
      String toString() {
        throw new RuntimeException('my exception')
      }
    }

    expect:
    convert([cs]) == ['error:my exception']
  }
}
