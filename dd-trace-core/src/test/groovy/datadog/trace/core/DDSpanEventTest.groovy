package datadog.trace.core

import datadog.trace.api.time.SystemTimeSource
import datadog.trace.api.time.TimeSource
import datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Shared

class DDSpanEventTest extends DDCoreSpecification {
  @Shared
  def mockTimeSource = Mock(TimeSource)
  @Shared
  def defaultTimestamp = 1234567890000000L

  def setup() {
    mockTimeSource = Mock(TimeSource) // Create a fresh mock for each test
    DDSpanEvent.setTimeSource(mockTimeSource)
  }

  def cleanup() {
    DDSpanEvent.setTimeSource(SystemTimeSource.INSTANCE)
  }

  def "test event creation with current time"() {
    given:
    mockTimeSource.getCurrentTimeNanos() >> defaultTimestamp
    def name = "test-event"
    def attributes = SpanNativeAttributes.builder()
      .put("key1", "value1")
      .put("key2", 123L)
      .build()

    when:
    def event = new DDSpanEvent(name, attributes)

    then:
    event.getName() == name
    event.getAttributes() == attributes
    event.getTimestampNanos() == defaultTimestamp
  }

  def "test event creation with explicit timestamp"() {
    given:
    def timestamp = 1742232412103000000L
    def name = "test-event"
    def attributes = SpanNativeAttributes.builder()
      .put("key1", "value1")
      .put("key2", 123L)
      .build()

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)

    then:
    0 * mockTimeSource.getCurrentTimeNanos()
    event.getName() == name
    event.getAttributes() == attributes
    event.getTimestampNanos() == timestamp
  }

  def "test time source change"() {
    given:
    def newTimeSource = Mock(TimeSource)
    def timestamp = 1742232412103000000L
    newTimeSource.getCurrentTimeNanos() >> timestamp

    when:
    DDSpanEvent.setTimeSource(newTimeSource)
    def event = new DDSpanEvent("test", SpanNativeAttributes.EMPTY)

    then:
    event.getTimestampNanos() == timestamp
  }

  def "test event creation with null attributes"() {
    given:
    mockTimeSource.getCurrentTimeNanos() >> defaultTimestamp
    def name = "test-event"

    when:
    def event = new DDSpanEvent(name, null)

    then:
    event.getName() == name
    event.getAttributes() == null
    event.getTimestampNanos() == defaultTimestamp
  }

  def "test event creation with empty attributes"() {
    given:
    mockTimeSource.getCurrentTimeNanos() >> defaultTimestamp
    def name = "test-event"
    def attributes = SpanNativeAttributes.EMPTY

    when:
    def event = new DDSpanEvent(name, attributes)

    then:
    event.getName() == name
    event.getAttributes() == attributes
    event.getTimestampNanos() == defaultTimestamp
  }

  def "test toJson with different attribute types"() {
    given:
    def timestamp = 1742232412103000000L
    def name = "test-event"
    def attributes = SpanNativeAttributes.builder()
      .put("boolean", true)
      .put("string", "value")
      .put("int", 42L)
      .put("long", 123L)
      .build()

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)
    def json = event.toJson()

    then:
    json == """{"time_unix_nano":${timestamp},"name":"${name}","attributes":{"boolean":true,"string":"value","int":42,"long":123}}"""
  }

  def "test toJson with array attribute types"() {
    given:
    def timestamp = 1742232412103000000L
    def name = "test-event"
    def attributes = SpanNativeAttributes.builder()
      .putStringArray("strings", ["a", "b", "c"])
      .putBooleanArray("booleans", [true, false, true])
      .putLongArray("longs", [1L, 2L, 3L])
      .putDoubleArray("doubles", [1.1d, 2.2d, 3.3d])
      .build()

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)
    def json = event.toJson()

    then:
    json == """{"time_unix_nano":${timestamp},"name":"${name}","attributes":{"strings":["a","b","c"],"booleans":[true,false,true],"longs":[1,2,3],"doubles":[1.1,2.2,3.3]}}"""
  }

  def "test toJson with null attributes"() {
    given:
    def timestamp = 1742232412103000000L
    def name = "test-event"

    when:
    def event = new DDSpanEvent(name, null, timestamp)
    def json = event.toJson()

    then:
    json == """{"time_unix_nano":${timestamp},"name":"${name}"}"""
  }

  def "test toJson with empty attributes"() {
    given:
    def timestamp = 1742232412103000000L
    def name = "test-event"
    def attributes = SpanNativeAttributes.EMPTY

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)
    def json = event.toJson()

    then:
    json == """{"time_unix_nano":${timestamp},"name":"${name}"}"""
  }

  def "test toJson with null values in attributes"() {
    setup:
    def eventName = "test-event"
    def attributes = SpanNativeAttributes.builder()
      // Test null values are skipped for all types
      .put("nullString", null as String)
      .put("nullBoolean", null as Boolean)
      .put("nullLong", null as Long)
      .put("nullDouble", null as Double)
      .putStringArray("nullStringArray", null)
      .putBooleanArray("nullBooleanArray", null)
      .putLongArray("nullLongArray", null)
      .putDoubleArray("nullDoubleArray", null)
      .build()
    def event = new DDSpanEvent(eventName, attributes)

    when:
    def json = event.toJson()

    then:
    def expectedJson = """{"time_unix_nano":${event.timestampNanos},"name":"test-event"}"""
    json == expectedJson
  }

  def "test toJson with empty arrays"() {
    given:
    def timestamp = 1742232412103000000L
    def name = "test-event"
    def attributes = SpanNativeAttributes.builder()
      .putStringArray("strings", [])
      .putBooleanArray("booleans", [])
      .putLongArray("longs", [])
      .putDoubleArray("doubles", [])
      .build()

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)
    def json = event.toJson()

    then:
    json == """{"time_unix_nano":${timestamp},"name":"${name}","attributes":{"strings":[],"booleans":[],"longs":[],"doubles":[]}}"""
  }

  def "test toTag with multiple events"() {
    given:
    def events = [
      new DDSpanEvent("event1", SpanNativeAttributes.builder().put("key1", "value1").build()),
      new DDSpanEvent("event2", SpanNativeAttributes.builder().put("key2", "value2").build())
    ]

    when:
    def tag = DDSpanEvent.toTag(events)

    then:
    tag.contains("event1")
    tag.contains("event2")
    tag.contains("key1")
    tag.contains("key2")
  }

  def "test toTag with empty events list"() {
    when:
    def tag = DDSpanEvent.toTag([])

    then:
    tag == null
  }

  def "test toTag with null events list"() {
    when:
    def tag = DDSpanEvent.toTag(null)

    then:
    tag == null
  }
}
