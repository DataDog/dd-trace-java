package com.datadog.appsec.event.data


import spock.lang.Specification

import java.nio.CharBuffer

import static com.datadog.appsec.event.data.ObjectIntrospection.convert

class ObjectIntrospectionSpecification extends Specification {
  void 'char sequences are converted to strings'() {
    setup:
    def charBuffer = CharBuffer.allocate(5)
    charBuffer.put('hello')
    charBuffer.position(0)

    expect:
    convert('hello') == 'hello'
    convert(charBuffer) == 'hello'
  }

  void 'numbers are converted to strings'() {
    expect:
    convert(5L) == '5'
    convert(0.33G) == '0.33'
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
  }

  void 'arrays are converted into lists'() {
    expect:
    convert([6, 'b'] as Object[]) == ['6', 'b']
    convert([1, 2] as int[]) == ['1', '2']
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
    convert(new ClassToBeConvertedExt()) == [c: 'd', a: 'b', l: ['1', '2']]
  }

  void 'max number of elements is honored'() {
    setup:
    def m = [:]
    128.times { m[it] = 'b' }

    expect:
    convert([['a'] * 255])[0].size() == 254 // +2 for the lists
    convert([['a'] * 255 as String[]])[0].size() == 254 // +2 for the lists
    convert(m).size() == 127
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

  def 'conversion of an element throws'() {
    setup:
    def cs = new CharSequence() {
        @Delegate String s = ''

        @Override
        String toString() {
          throw new RuntimeException('my exception')
        }
      }

    expect:
    convert([cs]) == ['<error: my exception>']
  }
}
