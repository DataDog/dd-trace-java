package datadog.trace.bootstrap.instrumentation.api


import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.test.util.DDSpecification

import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.SAMPLED_FLAG

class SpanLinkTest extends DDSpecification {
  def "test span link from context"() {
    setup:
    def traceId = DDTraceId.fromHex("11223344556677889900aabbccddeeff")
    def spanId = DDSpanId.fromHex("123456789abcdef0")
    AgentSpan.Context context = Stub {
      getTraceId() >> traceId
      getSpanId() >> spanId
      getSamplingPriority() >> (sampled ? 1 : 0)
    }

    when:
    def link = SpanLink.from(context)

    then:
    link.traceId() == traceId
    link.spanId() == spanId
    link.traceFlags() == (sampled ? SAMPLED_FLAG : DEFAULT_FLAGS)
    link.traceState() == ""
    link.attributes() == SpanLinkAttributes.EMPTY

    when:
    link.toString()

    then:
    notThrown(Exception)

    where:
    sampled << [true, false]
  }

  def "test span link attributes api"() {
    when:
    def attributes = SpanLinkAttributes.builder().build()

    then:
    attributes.isEmpty()

    when:
    attributes = SpanLinkAttributes.builder().put('key', 'value').build()

    then:
    !attributes.isEmpty()
  }

  def "test span link attributes encoding"() {
    setup:
    def builder = SpanLinkAttributes.builder()

    when:
    builder.put('string', 'value')
    builder.put('string-empty', '')
    builder.put('string-null', null)
    builder.put('bool', true)
    builder.put('bool-false', false)
    builder.put('long', 12345L)
    builder.put('long-negative', -12345L)
    builder.put('double', 67.89)
    builder.put('double-negative', -67.89)
    builder.putStringArray('string-array', ['abc', '', null, 'def'])
    builder.putStringArray('string-array-null', null)
    builder.putBooleanArray('bool-array', [true, false, null, Boolean.TRUE, Boolean.FALSE])
    builder.putStringArray('bool-array-null', null)
    builder.putLongArray('long-array', [123L, 456L, null, Long.MIN_VALUE, Long.MAX_VALUE])
    builder.putStringArray('long-array-null', null)
    builder.putDoubleArray('double-array', [12.3D, 45.6D, null, Double.MIN_VALUE, Double.MAX_VALUE])
    builder.putStringArray('double-array-null', null)
    def map = builder.build().asMap()

    then:
    map['string'] == 'value'
    map['string-empty'] == ''
    !map.containsKey('string-null')
    map['bool'] == 'true'
    map['bool-false'] == 'false'
    map['long'] == '12345'
    map['long-negative'] == '-12345'
    map['double'] == '67.89'
    map['double-negative'] == '-67.89'

    map['string-array.0'] == 'abc'
    map['string-array.1'] == ''
    !map.containsKey('string-array.2')
    map['string-array.3'] == 'def'
    !map.containsKey('string-array-null')

    map['bool-array.0'] == 'true'
    map['bool-array.1'] == 'false'
    !map.containsKey('bool-array.2')
    map['bool-array.3'] == 'true'
    map['bool-array.4'] == 'false'
    !map.containsKey('bool-array-null')

    map['long-array.0'] == '123'
    map['long-array.1'] == '456'
    !map.containsKey('long-array.2')
    map['long-array.3'] == '-9223372036854775808'
    map['long-array.4'] == '9223372036854775807'
    !map.containsKey('long-array-null')

    map['double-array.0'] == '12.3'
    map['double-array.1'] == '45.6'
    !map.containsKey('double-array.2')
    // Field should be encoded as String using JSON representation so the scientific notation is valid
    map['double-array.3'] == '4.9E-324'
    map['double-array.4'] == '1.7976931348623157E308'
    !map.containsKey('double-array-null')

    when:
    def attributes = SpanLinkAttributes.fromMap(map)

    then:
    attributes.asMap() == map
  }

  def "test span link attributes toString"() {
    when:
    SpanLinkAttributes.builder().build().toString()

    then:
    notThrown(NullPointerException)
  }
}
