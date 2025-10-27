package com.datadog.appsec.event.data

import com.datadog.appsec.gateway.AppSecRequestContext
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import spock.lang.Shared

import java.nio.CharBuffer

import static com.datadog.appsec.ddwaf.WAFModule.MAX_DEPTH
import static com.datadog.appsec.ddwaf.WAFModule.MAX_ELEMENTS
import static com.datadog.appsec.ddwaf.WAFModule.MAX_STRING_SIZE
import static com.datadog.appsec.event.data.ObjectIntrospection.convert

@SuppressWarnings('UnnecessaryBooleanExpression')
class ObjectIntrospectionSpecification extends DDSpecification {

  @Shared
  protected static final ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get()

  @Shared
  protected static final MAPPER = new ObjectMapper()

  AppSecRequestContext ctx = Mock(AppSecRequestContext)

  WafMetricCollector wafMetricCollector = Mock(WafMetricCollector)

  void setup() {
    WafMetricCollector.INSTANCE = wafMetricCollector
  }

  void cleanup() {
    WafMetricCollector.INSTANCE  = ORIGINAL_METRIC_COLLECTOR
  }

  void 'null is preserved'() {
    expect:
    convert(null, ctx) == null
  }

  void 'type #type is preserved'() {
    when:
    def result = convert(input, ctx)

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
    def result = convert(input, ctx)

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
    convert(iter, ctx) instanceof List
    convert(iter, ctx) == ['a', 'b']
    convert(['a', 'b'], ctx) == ['a', 'b']
  }

  void 'maps are converted to hash maps'() {
    setup:
    def map = new Map() {
      @Delegate Map map = [a: 'b']
    }

    expect:
    convert(map, ctx) instanceof HashMap
    convert(map, ctx) == [a: 'b']
    convert([(6): 'b'], ctx) == ['6': 'b']
    convert([(null): 'b'], ctx) == ['null': 'b']
    convert([(true): 'b'], ctx) == ['true': 'b']
    convert([('a' as Character): 'b'], ctx) == ['a': 'b']
    convert([(createCharBuffer('a')): 'b'], ctx) == ['a': 'b']
  }

  void 'arrays are converted into lists'() {
    expect:
    convert([6, 'b'] as Object[], ctx) == [6, 'b']
    convert([null, null] as Object[], ctx) == [null, null]
    convert([1, 2] as int[], ctx) == [1 as int, 2 as int]
    convert([1, 2] as byte[], ctx) == [1 as byte, 2 as byte]
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
    convert(new ClassToBeConverted(), ctx) instanceof HashMap
    convert(new ClassToBeConvertedExt(), ctx) == [c: 'd', a: 'b', l: [1, 2]]
  }

  class ProtobufLikeClass {
    String c = 'd'
    int memoizedHashCode = 1
    int memoizedSize = 2
  }

  void 'some field names are ignored'() {
    expect:
    convert(new ProtobufLikeClass(), ctx) instanceof HashMap
    convert(new ProtobufLikeClass(), ctx) == [c: 'd']
  }

  void 'invalid keys are converted to special strings'() {
    expect:
    convert(Collections.singletonMap(new ClassToBeConverted(), 'a'), ctx) == ['invalid_key:1': 'a']
    convert([new ClassToBeConverted(): 'a', new ClassToBeConverted(): 'b'], ctx) == ['invalid_key:1': 'a', 'invalid_key:2': 'b']
    convert(Collections.singletonMap([1, 2], 'a'), ctx) == ['invalid_key:1': 'a']
  }

  void 'max number of elements is honored'() {
    setup:
    def m = [:]
    128.times { m[it] = 'b' }

    when:
    def result1 = convert([['a'] * 255], ctx)[0]
    def result2 = convert([['a'] * 255 as String[]], ctx)[0]
    def result3 = convert(m, ctx)

    then:
    result1.size() == 254 // +2 for the lists
    result2.size() == 254 // +2 for the lists
    result3.size() == 127  // +1 for the map, 2 for each entry (key and value)
    2 * ctx.setWafTruncated()
    2 * wafMetricCollector.wafInputTruncated(false, true, false)
  }

  void 'max depth is honored — array version'() {
    setup:
    // Build a nested array 22 levels deep
    Object[] objArray = new Object[1]
    def p = objArray
    22.times { p = p[0] = new Object[1] }

    when:
    // Invoke conversion with context
    def result = convert(objArray, ctx)

    then:
    // Traverse converted arrays to count actual depth
    int depth = 0
    for (p = result; p != null; p = p[0]) {
      depth++
    }
    depth == 21 // after max depth we have nulls

    // Should record a truncation due to depth
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(false, false, true)
  }

  void 'max depth is honored — list version'() {
    setup:
    // Build a nested list 22 levels deep
    def list = []
    def p = list
    22.times { p << []; p = p[0] }

    when:
    // Invoke conversion with context
    def result = convert(list, ctx)

    then:
    // Traverse converted lists to count actual depth
    int depth = 0
    for (p = result; p != null; p = p[0]) {
      depth++
    }
    depth == 21 // after max depth we have nulls

    // Should record a truncation due to depth
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(false, false, true)
  }

  def 'max depth is honored — map version'() {
    setup:
    // Build a nested map 22 levels deep under key 'a'
    def map = [:]
    def p = map
    22.times { p['a'] = [:]; p = p['a'] }

    when:
    // Invoke conversion with context
    def result = convert(map, ctx)

    then:
    // Traverse converted maps to count actual depth
    int depth = 0
    for (p = result; p != null; p = p['a']) {
      depth++
    }
    depth == 21 // after max depth we have nulls

    // Should record a truncation due to depth
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(false, false, true)
  }

  void 'truncate long #typeName to 4096 chars and set truncation flag'() {
    setup:
    def longInput = rawInput

    when:
    def converted   = convert(longInput, ctx)

    then:
    // Should always produce a String of exactly 4096 chars
    converted instanceof String
    converted.length() == 4096

    // Should record a truncation due to string length
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(true, false, false)

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
    def converted   = convert(inputMap, ctx)

    then:
    // Extract the single truncated key
    def truncatedKey = converted.keySet().iterator().next() as String

    // Key must be exactly 4096 characters
    truncatedKey.length() == 4096

    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(true, false, false)


    where:
    typeName        | rawKey
    'String'        | 'A' * 5000
    'StringBuilder' | new StringBuilder('B' * 6000)
  }

  void 'conversion of an element throws'() {
    setup:
    def cs = new CharSequence() {
      // When run on JDK8 this prevents `NoClassDefFoundError` of `java.lang.constant.Constable`.
      // Since JDK12 types like Integer, String implement Constable, and groovy capture this
      // in the enclosing class, which fails on JDK8 when it is loaded,
      // see https://github.com/groovy/groovy-eclipse/issues/1436
      @Delegate(interfaces = false) String s = ''

      @Override
      String toString() {
        throw new RuntimeException('my exception')
      }
    }

    expect:
    convert([cs], ctx) == ['error:my exception']
  }

  void 'truncated conversion triggers truncation listener if available '() {
    setup:
    def listener = Mock(ObjectIntrospection.TruncationListener)
    def object = 'A' * 5000

    when:
    convert(object, ctx, listener)

    then:
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(true, false, false)
    1 * listener.onTruncation()
  }

  void 'jackson node types comprehensive coverage'() {
    when:
    final result = convert(input, ctx)

    then:
    result == expected

    where:
    input                                          || expected
    MAPPER.readTree('null')                        || null
    MAPPER.readTree('true')                        || true
    MAPPER.readTree('false')                       || false
    MAPPER.readTree('42')                          || 42
    MAPPER.readTree('3.14')                        || 3.14
    MAPPER.readTree('"hello"')                     || 'hello'
    MAPPER.readTree('[]')                          || []
    MAPPER.readTree('{}')                          || [:]
    MAPPER.readTree('[1, 2, 3]')                   || [1, 2, 3]
    MAPPER.readTree('{"key": "value"}')            || [key: 'value']
  }

  void 'jackson nested structures'() {
    when:
    final result = convert(input, ctx)

    then:
    result == expected

    where:
    input                                          || expected
    MAPPER.readTree('{"a": {"b": {"c": 123}}}')    || [a: [b: [c: 123]]]
    MAPPER.readTree('[[[1, 2]], [[3, 4]]]')        || [[[1, 2]], [[3, 4]]]
    MAPPER.readTree('{"arr": [1, null, true]}')    || [arr: [1, null, true]]
    MAPPER.readTree('[{"x": 1}, {"y": 2}]')        || [[x: 1], [y: 2]]
  }

  void 'jackson edge cases'() {
    when:
    final result = convert(input, ctx)

    then:
    result == expected

    where:
    input                                          || expected
    MAPPER.readTree('""')                          || ''
    MAPPER.readTree('0')                           || 0
    MAPPER.readTree('-1')                          || -1
    MAPPER.readTree('9223372036854775807')         || 9223372036854775807L  // Long.MAX_VALUE
    MAPPER.readTree('1.7976931348623157E308')      || 1.7976931348623157E308d  // Double.MAX_VALUE
    MAPPER.readTree('{"": "empty_key"}')           || ['': 'empty_key']
    MAPPER.readTree('{"null_value": null}')        || [null_value: null]
  }

  void 'jackson string truncation'() {
    setup:
    final longString = 'A' * (MAX_STRING_SIZE + 1)
    final jsonInput = '{"long": "' + longString + '"}'

    when:
    final result = convert(MAPPER.readTree(jsonInput), ctx)

    then:
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(true, false, false)
    result["long"].length() <= MAX_STRING_SIZE
  }

  void 'jackson with deep nesting triggers depth limit'() {
    setup:
    // Create deeply nested JSON
    final json = JsonOutput.toJson(
    (1..(MAX_DEPTH + 1)).inject([:], { result, i -> [("child_$i".toString()) : result] })
    )

    when:
    final result = convert(MAPPER.readTree(json), ctx)

    then:
    // Should truncate at max depth and set truncation flag
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(false, false, true)
    countNesting(result as Map, 0) <= MAX_DEPTH
  }

  void 'jackson with large arrays triggers element limit'() {
    setup:
    // Create large array
    final largeArray = (1..(MAX_ELEMENTS + 1)).toList()
    final json = new JsonBuilder(largeArray).toString()

    when:
    final result = convert(MAPPER.readTree(json), ctx) as List

    then:
    // Should truncate and set truncation flag
    1 * ctx.setWafTruncated()
    1 * wafMetricCollector.wafInputTruncated(false, true, false)
    result.size() <= MAX_ELEMENTS
  }

  void 'jackson number type variations'() {
    when:
    final result = convert(input, ctx)

    then:
    result == expected

    where:
    input                                          || expected
    MAPPER.readTree('0')                           || 0
    MAPPER.readTree('1')                           || 1
    MAPPER.readTree('-1')                          || -1
    MAPPER.readTree('1.0')                         || 1.0
    MAPPER.readTree('1.5')                         || 1.5
    MAPPER.readTree('-1.5')                        || -1.5
    MAPPER.readTree('1e10')                        || 1e10
    MAPPER.readTree('1.23e-4')                     || 1.23e-4
  }

  void 'jackson special string values'() {
    when:
    final result = convert(input, ctx)

    then:
    result == expected

    where:
    input                                          || expected
    MAPPER.readTree('"\\n"')                       || '\n'
    MAPPER.readTree('"\\t"')                       || '\t'
    MAPPER.readTree('"\\r"')                       || '\r'
    MAPPER.readTree('"\\\\"')                      || '\\'
    MAPPER.readTree('"\\"quotes\\""')              || '"quotes"'
    MAPPER.readTree('"unicode: \\u0041"')          || 'unicode: A'
  }

  void 'iterable json objects'() {
    setup:
    final map = [name: 'This is just a test', list: [1, 2, 3, 4, 5]]

    when:
    final result = convert(new IterableJsonObject(map), ctx)

    then:
    result == map
  }

  private static int countNesting(final Map<String, Object>object, final int levels) {
    if (object.isEmpty()) {
      return levels
    }
    final child = object.values().first()
    if (child == null) {
      return levels
    }
    return countNesting(object.values().first() as Map, levels + 1)
  }

  private static class IterableJsonObject implements Iterable<Map.Entry<String, Object>> {

    private final Map<String, Object> map

    IterableJsonObject(Map<String, Object> map) {
      this.map = map
    }

    @Override
    Iterator<Map.Entry<String, Object>> iterator() {
      return map.entrySet().iterator()
    }
  }
}
