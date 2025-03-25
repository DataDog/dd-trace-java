package datadog.trace.bootstrap.instrumentation.api

import datadog.trace.test.util.DDSpecification
import spock.lang.Subject

class SpanNativeAttributesTest extends DDSpecification {

  @Subject
  SpanNativeAttributes attributes

  def "empty attributes"() {
    when:
    attributes = SpanNativeAttributes.EMPTY

    then:
    attributes.isEmpty()
    attributes.data().isEmpty()
  }

  def "builder creates empty attributes"() {
    when:
    attributes = SpanNativeAttributes.builder().build()

    then:
    attributes.isEmpty()
    attributes.data().isEmpty()
  }

  def "builder adds AttributeKey attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
            .put(SpanNativeAttributes.AttributeKey.stringKey("key"), "value")
            .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.stringKey("key")) == "value"
  }

  def "builder adds typed attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      ."${method}"("key", value)
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey."${keyType}"("key")) == value

    where:
    method           | keyType          | value
    'put'            | 'stringKey'      | 'value'
    'put'            | 'booleanKey'     | true
    'put'            | 'longKey'        | 123L
    'put'            | 'doubleKey'      | 42.0d
    'putStringArray' | 'stringArrayKey' | ['a', 'b']
    'putBooleanArray'| 'booleanArrayKey'| [true, false]
    'putLongArray'   | 'longArrayKey'   | [1L, 2L]
    'putDoubleArray' | 'doubleArrayKey' | [1.1d, 2.2d]
  }

  def "builder skips null values"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .put("key1", "value")
      .put("key2", null as String)
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.stringKey("key1")) == "value"
    !attributes.data().containsKey(SpanNativeAttributes.AttributeKey.stringKey("key2"))
  }

  def "builder requires non-null keys for AttributeKey"() {
    when:
    SpanNativeAttributes.builder().put(null as SpanNativeAttributes.AttributeKey, "value")

    then:
    thrown(NullPointerException)
  }

  def "builder requires non-null keys for all attribute types"() {
    when:
    SpanNativeAttributes.builder()."${method}"(null, value)

    then:
    thrown(NullPointerException)

    where:
    method            | value
    'put'             | 'string value'
    'put'             | true
    'put'             | 123L
    'put'             | 42.0d
    'putStringArray'  | ['a', 'b']
    'putBooleanArray' | [true, false]
    'putLongArray'    | [1L, 2L]
    'putDoubleArray'  | [1.1d, 2.2d]
  }

  def "builder skips null values for all attribute types"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .put(SpanNativeAttributes.AttributeKey.stringKey("string"), null as String)
      .put('string', null as String)
      .put('boolean', null as Boolean)
      .put('long', null as Long)
      .put('double', null as Double)
      .putStringArray('stringArray', null)
      .putBooleanArray('booleanArray', null)
      .putLongArray('longArray', null)
      .putDoubleArray('doubleArray', null)
      .build()

    then:
    attributes.isEmpty()
    attributes.data().isEmpty()
  }

  def "equals compares attributes correctly"() {
    setup:
    def builder1 = SpanNativeAttributes.builder()
      .put("key1", "value1")
      .put("key2", 123L)
    def builder2 = SpanNativeAttributes.builder()
      .put("key1", "value1")
      .put("key2", 123L)
    def builder3 = SpanNativeAttributes.builder()
      .put("key1", "value1")
      .put("key2", 456L)

    when:
    def attrs1 = builder1.build()
    def attrs2 = builder2.build()
    def attrs3 = builder3.build()

    then:
    attrs1 == attrs2
    attrs1 != attrs3
    attrs2 != attrs3
  }

  def "AttributeKey has the correct values"() {
    when:
    def key = SpanNativeAttributes.AttributeKey.stringKey("key")

    then:
    key.getKey() == "key"
    key.getType() == SpanNativeAttributes.AttributeType.STRING
    key.toString() == "AttributeKey{key, STRING}"
    
    key.compareTo(SpanNativeAttributes.AttributeKey.stringKey("key")) == 0
    key.equals(SpanNativeAttributes.AttributeKey.stringKey("key"))
    key.hashCode() == SpanNativeAttributes.AttributeKey.stringKey("key").hashCode()

    key.equals(SpanNativeAttributes.AttributeKey.stringKey("other")) == false
    key.compareTo(SpanNativeAttributes.AttributeKey.stringKey("other")) != 0
    key.hashCode() != SpanNativeAttributes.AttributeKey.stringKey("other").hashCode()
  }

  def "toString includes all attributes"() {
    setup:
    attributes = SpanNativeAttributes.builder()
      .put("key1", "value1")
      .put("key2", 123L)
      .build()

    when:
    def string = attributes.toString()

    then:
    string.contains("key1")
    string.contains("value1")
    string.contains("key2")
    string.contains("123")
  }
}
