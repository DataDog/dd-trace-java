package datadog.trace.common.writer

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification

class DDSpanJsonAdapterTest extends DDCoreSpecification {
  def tracer = tracerBuilder().writer(new ListWriter()).build()
  def adapter = new Moshi.Builder()
  .add(DDSpanJsonAdapter.buildFactory(false))
  .build()
  .adapter(Types.newParameterizedType(List, DDSpan))
  def genericAdapter = new Moshi.Builder().build().adapter(Object)

  def "test span event serialization"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def eventName = "test-event"
    def attributes = ["key1": "value1", "key2": 123]
    def timestamp = System.currentTimeMillis()

    when: "adding event with name and attributes"
    span.addEvent(eventName, attributes, timestamp, java.util.concurrent.TimeUnit.MILLISECONDS)
    span.finish()
    def jsonStr = adapter.toJson([span])

    then: "event is serialized correctly in meta section"
    def actual = genericAdapter.fromJson(jsonStr)
    def actualSpan = actual[0]

    // Verify basic span fields
    actualSpan.service == span.getServiceName()
    actualSpan.name == span.getOperationName()
    actualSpan.resource == span.getResourceName()
    actualSpan.trace_id == span.getTraceId().toLong()
    actualSpan.span_id == span.getSpanId()
    actualSpan.parent_id == span.getParentId()
    actualSpan.start == span.getStartTime()
    actualSpan.duration == span.getDurationNano()
    actualSpan.error == span.getError()
    actualSpan.type == span.getSpanType()

    // Verify span events
    def actualEvents = actualSpan.meta["_dd.span_events"]
    def expectedEvent = "[{\"time_unix_nano\":${java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timestamp)},\"name\":\"test-event\",\"attributes\":{\"key1\":\"value1\",\"key2\":123}}]"
    actualEvents.toString() == expectedEvent.toString()

    cleanup:
    tracer.close()
  }

  def "test multiple span events serialization"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def timestamp1 = System.currentTimeMillis()
    def timestamp2 = timestamp1 + 1000

    when: "adding multiple events"
    span.addEvent("event1", ["key1": "value1"], timestamp1, java.util.concurrent.TimeUnit.MILLISECONDS)
    span.addEvent("event2", ["key2": "value2"], timestamp2, java.util.concurrent.TimeUnit.MILLISECONDS)
    span.finish()
    def jsonStr = adapter.toJson([span])

    then: "events are serialized correctly in meta section"
    def actual = genericAdapter.fromJson(jsonStr)
    def actualSpan = actual[0]

    // Verify basic span fields
    actualSpan.service == span.getServiceName()
    actualSpan.name == span.getOperationName()
    actualSpan.resource == span.getResourceName()
    actualSpan.trace_id == span.getTraceId().toLong()
    actualSpan.span_id == span.getSpanId()
    actualSpan.parent_id == span.getParentId()
    actualSpan.start == span.getStartTime()
    actualSpan.duration == span.getDurationNano()
    actualSpan.error == span.getError()
    actualSpan.type == span.getSpanType()

    // Verify span events
    def actualEvents = actualSpan.meta["_dd.span_events"]

    def expectedEvents = "[{\"time_unix_nano\":${java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timestamp1)},\"name\":\"event1\",\"attributes\":{\"key1\":\"value1\"}},{\"time_unix_nano\":${java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timestamp2)},\"name\":\"event2\",\"attributes\":{\"key2\":\"value2\"}}]"

    actualEvents.toString() == expectedEvents.toString()


    cleanup:
    tracer.close()
  }

  def "test span events added directly as tag"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def eventsJson = """[
      {
        "time_unix_nano": 1234567890000000,
        "name": "manual-event",
        "attributes": {
          "foo": "bar",
          "count": 42
        }
      }
    ]"""

    when: "adding events JSON directly as a tag"
    span.setTag("_dd.span_events", eventsJson)
    span.finish()
    def jsonStr = adapter.toJson([span])

    then: "events JSON is preserved exactly in meta section"
    def actual = genericAdapter.fromJson(jsonStr)
    def actualSpan = actual[0]

    // Verify basic span fields
    actualSpan.service == span.getServiceName()
    actualSpan.name == span.getOperationName()
    actualSpan.resource == span.getResourceName()
    actualSpan.trace_id == span.getTraceId().toLong()
    actualSpan.span_id == span.getSpanId()
    actualSpan.parent_id == span.getParentId()
    actualSpan.start == span.getStartTime()
    actualSpan.duration == span.getDurationNano()
    actualSpan.error == span.getError()
    actualSpan.type == span.getSpanType()

    // Verify span events
    def actualEvents = actualSpan.meta["_dd.span_events"]
    actualEvents.toString() == eventsJson

    cleanup:
    tracer.close()
  }
}