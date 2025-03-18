package datadog.trace.bootstrap.instrumentation.api

import datadog.trace.api.time.SystemTimeSource
import datadog.trace.api.time.TimeSource
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
    def attributes = ["key1": "value1", "key2": 123]

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
    def attributes = ["key1": "value1", "key2": 123]

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)

    then:
    0 * mockTimeSource.getCurrentTimeNanos()
    event.getName() == name
    event.getAttributes() == attributes
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
    def attributes = [:]

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
    def attributes = [
      "string": "value",
      "number": 42,
      "boolean": true,
      "null": null
    ]

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)
    def json = event.toJson()

    then:
    json == """{"time_unix_nano":${timestamp},"name":"${name}","attributes":{"string":"value","number":42,"boolean":true,"null":null}}"""
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
    def attributes = [:]

    when:
    def event = new DDSpanEvent(name, attributes, timestamp)
    def json = event.toJson()

    then:
    json == """{"time_unix_nano":${timestamp},"name":"${name}"}"""
  }

  def "test time source change"() {
    given:
    def newTimeSource = Mock(TimeSource)
    def timestamp = 1742232412103000000L
    newTimeSource.getCurrentTimeNanos() >> timestamp

    when:
    DDSpanEvent.setTimeSource(newTimeSource)
    def event = new DDSpanEvent("test", [:])

    then:
    event.getTimestampNanos() == timestamp
  }
}