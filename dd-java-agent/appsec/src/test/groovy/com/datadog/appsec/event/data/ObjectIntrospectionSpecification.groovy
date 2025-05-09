package com.datadog.appsec.event.data

import spock.lang.Specification

import java.nio.CharBuffer

import static com.datadog.appsec.event.data.ObjectIntrospection.convert

class ObjectIntrospectionSpecification extends Specification {

  void 'null is preserved'() {
    expect:
    def pair =  convert(null)
    pair.getLeft() == null
    pair.getRight() == false
  }

  void 'type #type is preserved'() {
    when:
    def pair = convert(input)
    def result = pair.getLeft()

    then:
    input.getClass() == type
    result.getClass() == type
    result == input

    where:
    input        | type
    'hello'      | String
    true         | Boolean
    (byte) 1     | Byte
    (short) 1    | Short
    1            | Integer
    1L           | Long
    1.0F         | Float
    (double) 1.0 | Double
    1G           | BigInteger
    1.0G         | BigDecimal
  }

  void 'type #type is converted to string'() {
    when:
    def pair = convert(input)
    def result = pair.getLeft()

    then:
    type.isAssignableFrom(input.getClass())
    result instanceof String
    result == output

    where:
    input                     | type       || output
    (char) 'a'                | Character  || 'a'
    createCharBuffer('hello') | CharBuffer || 'hello'
  }

  static CharBuffer createCharBuffer(String s) {
    def charBuffer = CharBuffer.allocate(s.length())
    charBuffer.put(s)
    charBuffer.position(0)
    charBuffer
  }

  void 'iterables are converted to lists'() {
    setup:
    def iter = new Iterable() {
      @Delegate Iterable delegate = ['a', 'b']
    }

    expect:
    convert(iter).getLeft() instanceof List
    convert(iter).getLeft() == ['a', 'b']
    convert(['a', 'b']).getLeft() == ['a', 'b']
  }

  void 'maps are converted to hash maps'() {
    setup:
    def map = new Map() {
      @Delegate Map map = [a: 'b']
    }

    expect:
    convert(map).getLeft() instanceof HashMap
    convert(map).getLeft() == [a: 'b']
    convert([(6): 'b']).getLeft() == ['6': 'b']
    convert([(null): 'b']).getLeft() == ['null': 'b']
    convert([(true): 'b']).getLeft() == ['true': 'b']
    convert([('a' as Character): 'b']).getLeft() == ['a': 'b']
    convert([(createCharBuffer('a')): 'b']).getLeft() == ['a': 'b']
  }

  void 'arrays are converted into lists'() {
    expect:
    convert([6, 'b'] as Object[]).getLeft() == [6, 'b']
    convert([null, null] as Object[]).getLeft() == [null, null]
    convert([1, 2] as int[]).getLeft() == [1 as int, 2 as int]
    convert([1, 2] as byte[]).getLeft() == [1 as byte, 2 as byte]
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
    convert(new ClassToBeConverted()).getLeft() instanceof HashMap
    convert(new ClassToBeConvertedExt()).getLeft()== [c: 'd', a: 'b', l: [1, 2]]
  }

  class ProtobufLikeClass {
    String c = 'd'
    int memoizedHashCode = 1
    int memoizedSize = 2
  }

  void 'some field names are ignored'() {
    expect:
    convert(new ProtobufLikeClass()).getLeft() instanceof HashMap
    convert(new ProtobufLikeClass()).getLeft() == [c: 'd']
  }

  void 'invalid keys are converted to special strings'() {
    expect:
    convert(Collections.singletonMap(new ClassToBeConverted(), 'a')).getLeft() == ['invalid_key:1': 'a']
    convert([new ClassToBeConverted(): 'a', new ClassToBeConverted(): 'b']).getLeft() == ['invalid_key:1': 'a', 'invalid_key:2': 'b']
    convert(Collections.singletonMap([1, 2], 'a')).getLeft() == ['invalid_key:1': 'a']
  }

  void 'max number of elements is honored'() {
    setup:
    def m = [:]
    128.times { m[it] = 'b' }

    expect:
    convert([['a'] * 255]).getLeft()[0].size() == 254 // +2 for the lists
    convert([['a'] * 255 as String[]]).getLeft()[0].size() == 254 // +2 for the lists
    convert(m).getLeft().size() == 127 // +1 for the map, 2 for each entry (key and value)
  }

  void 'max depth is honored — array version with truncation flag'() {
    setup:
    // Create a 1-element array and build 22 nested arrays
    Object[] objArray = new Object[1]
    def p = objArray
    22.times { p = p[0] = new Object[1] }

    when:
    // convert now returns a Pair: getLeft() = converted object, getRight() = truncation flag
    def resultPair = convert(objArray)
    Object[] converted = resultPair.getLeft()
    boolean wasTruncated = resultPair.getRight()

    then:
    // Traverse the converted nested arrays to count actual depth
    int depth = 0
    for (p = converted; p != null; p = p[0]) {
      depth++
    }

    // Expect exactly 21 levels (root + 20 allowed) before hitting null
    depth == 21

    // And the truncation flag should be true, since we exceeded max depth
    wasTruncated
  }

  void 'max depth is honored — list version with truncation flag'() {
    setup:
    // Build a nested list 22 levels deep
    def list = []
    def p = list
    22.times {
      p << []
      p = p[0]
    }

    when:
    // convert now returns a Pair: getLeft() = converted nested structure, getRight() = truncation flag
    def resultPair = convert(list)
    List<?> converted = resultPair.getLeft()
    boolean wasTruncated = resultPair.getRight()

    then:
    // Traverse the converted lists to count actual depth
    int depth = 0
    for (p = converted; p != null; p = p[0]) {
      depth++
    }

    // Expect exactly 21 levels (root + 20 allowed) before hitting null
    depth == 21

    // And the truncation flag should be true, since we exceeded max depth
    wasTruncated
  }

  void 'max depth is honored — map version with truncation flag'() {
    setup:
    // Build a nested map 22 levels deep under key 'a'
    def map = [:]
    def p = map
    22.times {
      p['a'] = [:]
      p = p['a']
    }

    when:
    // convert now returns a Pair: getLeft() = converted nested structure, getRight() = truncation flag
    def resultPair = convert(map)
    Map<?,?> converted = resultPair.getLeft()
    boolean wasTruncated = resultPair.getRight()

    then:
    // Traverse the converted maps to count actual depth
    int depth = 0
    for (p = converted; p != null; p = p['a']) {
      depth++
    }

    // Expect exactly 21 levels (root + 20 allowed) before hitting null
    depth == 21

    // And the truncation flag should be true, since we exceeded max depth
    wasTruncated
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
    convert([cs]).getLeft() == ['error:my exception']
  }

  void 'truncate long #typeName to 4096 chars and set truncation flag'() {
    setup:
    def longInput = rawInput

    when:
    def resultPair   = convert(longInput)
    def converted    = resultPair.getLeft()
    def wasTruncated = resultPair.getRight()

    then:
    // Should always produce a String of exactly 4096 chars
    converted instanceof String
    converted.length() == 4096

    // And the truncation flag must be true
    wasTruncated

    where:
    typeName        | rawInput
    'String'        | 'A' * 5000
    'StringBuilder' | new StringBuilder('B' * 6000)
  }

  void 'truncate long #typeName when used as map key to 4096 chars and set truncation flag'() {
    setup:
    // Build a map whose only key is a very long string/CharSequence
    def longKey = rawKey
    def inputMap = [(longKey): 'value']

    when:
    // convert returns Pair<convertedObject, wasTruncated>
    def resultPair   = convert(inputMap)
    Map<?,?> convertedMap = resultPair.getLeft()
    def wasTruncated = resultPair.getRight()

    then:
    // Extract the single truncated key
    def truncatedKey = convertedMap.keySet().iterator().next() as String

    // Key must be exactly 4096 characters
    truncatedKey.length() == 4096

    // And the truncation flag must be true
    wasTruncated

    where:
    typeName        | rawKey
    'String'        | 'A' * 5000
    'StringBuilder' | new StringBuilder('B' * 6000)
  }
}
