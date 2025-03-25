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

  def "builder adds string attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .put("key", "value")
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.stringKey("key")) == "value"
  }

  def "builder adds boolean attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .put("key", true)
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.booleanKey("key")) == true
  }

  def "builder adds long attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .put("key", 123L)
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.longKey("key")) == 123L
  }

  def "builder adds double attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .put("key", 42.0)
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.doubleKey("key")) == 42.0
  }

  def "builder adds string array attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .putStringArray("key", ["a", "b"])
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.stringArrayKey("key")) == ["a", "b"]
  }

  def "builder adds boolean array attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .putBooleanArray("key", [true, false])
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.booleanArrayKey("key")) == [true, false]
  }

  def "builder adds long array attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .putLongArray("key", [1L, 2L])
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.longArrayKey("key")) == [1L, 2L]
  }

  def "builder adds double array attribute"() {
    when:
    attributes = SpanNativeAttributes.builder()
      .putDoubleArray("key", [1.1, 2.2])
      .build()

    then:
    !attributes.isEmpty()
    attributes.data().size() == 1
    attributes.data().get(SpanNativeAttributes.AttributeKey.doubleArrayKey("key")) == [1.1, 2.2]
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

  def "builder requires non-null keys"() {
    when:
    SpanNativeAttributes.builder().put(null, "value")

    then:
    thrown(NullPointerException)
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
    attrs1 != null
    attrs1 != "not an attributes object"
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
