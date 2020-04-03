package datadog.opentracing

import datadog.trace.core.DDSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.util.test.DDSpecification
import io.opentracing.Span

class SpanBuilderTest extends DDSpecification {
  // TODO more io.opentracing.SpanBuilder specific tests

  def writer = new ListWriter()
  def tracer = DDTracerOT.builder().writer(writer).build()

  def "should inherit the DD parent attributes addReference CHILD_OF"() {
    setup:
    def expectedName = "fakeName"
    def expectedParentServiceName = "fakeServiceName"
    def expectedParentResourceName = "fakeResourceName"
    def expectedParentType = "fakeType"
    def expectedChildServiceName = "fakeServiceName-child"
    def expectedChildResourceName = "fakeResourceName-child"
    def expectedChildType = "fakeType-child"
    def expectedBaggageItemKey = "fakeKey"
    def expectedBaggageItemValue = "fakeValue"

    final Span parent =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .withResourceName(expectedParentResourceName)
        .withSpanType(expectedParentType)
        .start()

    parent.setBaggageItem(expectedBaggageItemKey, expectedBaggageItemValue)

    // ServiceName and SpanType are always set by the parent  if they are not present in the child
    Span span =
      tracer
        .buildSpan(expectedName)
        .withServiceName(expectedParentServiceName)
        .addReference("child_of", parent.context())
        .start()

    expect:
    span.delegate.getOperationName() == expectedName
    span.getBaggageItem(expectedBaggageItemKey) == expectedBaggageItemValue
    span.context().delegate.getServiceName() == expectedParentServiceName
    span.context().delegate.getResourceName() == expectedName
    span.context().delegate.getSpanType() == null

    when:
    // ServiceName and SpanType are always overwritten by the child  if they are present
    span =
      tracer
        .buildSpan(expectedName)
        .withServiceName(expectedChildServiceName)
        .withResourceName(expectedChildResourceName)
        .withSpanType(expectedChildType)
        .addReference("child_of", parent.context())
        .start()

    then:
    span.delegate.getOperationName() == expectedName
    span.getBaggageItem(expectedBaggageItemKey) == expectedBaggageItemValue
    span.context().delegate.getServiceName() == expectedChildServiceName
    span.context().delegate.getResourceName() == expectedChildResourceName
    span.context().delegate.getSpanType() == expectedChildType
  }


  def "should inherit the DD parent attributes add reference FOLLOWS_FROM"() {
    setup:
    def expectedName = "fakeName"
    def expectedParentServiceName = "fakeServiceName"
    def expectedParentResourceName = "fakeResourceName"
    def expectedParentType = "fakeType"
    def expectedChildServiceName = "fakeServiceName-child"
    def expectedChildResourceName = "fakeResourceName-child"
    def expectedChildType = "fakeType-child"
    def expectedBaggageItemKey = "fakeKey"
    def expectedBaggageItemValue = "fakeValue"

    final Span parent =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .withResourceName(expectedParentResourceName)
        .withSpanType(expectedParentType)
        .start()

    parent.setBaggageItem(expectedBaggageItemKey, expectedBaggageItemValue)

    // ServiceName and SpanType are always set by the parent  if they are not present in the child
    Span span =
      tracer
        .buildSpan(expectedName)
        .withServiceName(expectedParentServiceName)
        .addReference("follows_from", parent.context())
        .start()

    expect:
    span.delegate.getOperationName() == expectedName
    span.getBaggageItem(expectedBaggageItemKey) == expectedBaggageItemValue
    span.context().delegate.getServiceName() == expectedParentServiceName
    span.context().delegate.getResourceName() == expectedName
    span.context().delegate.getSpanType() == null

    when:
    // ServiceName and SpanType are always overwritten by the child  if they are present
    span =
      tracer
        .buildSpan(expectedName)
        .withServiceName(expectedChildServiceName)
        .withResourceName(expectedChildResourceName)
        .withSpanType(expectedChildType)
        .addReference("follows_from", parent.context())
        .start()

    then:
    span.delegate.getOperationName() == expectedName
    span.getBaggageItem(expectedBaggageItemKey) == expectedBaggageItemValue
    span.context().delegate.getServiceName() == expectedChildServiceName
    span.context().delegate.getResourceName() == expectedChildResourceName
    span.context().delegate.getSpanType() == expectedChildType
  }

  def "sanity test for logs if logHandler is null"() {
    setup:
    final String expectedName = "fakeName"

    final Span span =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .start()
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    final Map<String, String> fieldsMap = new HashMap<>()

    span.log(expectedLogEvent)
    span.log(timeStamp, expectedLogEvent)
    span.log(fieldsMap)
    span.log(timeStamp, fieldsMap)
  }

  def "sanity test when passed log handler is null"() {
    setup:
    final String expectedName = "fakeName"
    final Span span = tracer
      .buildSpan(expectedName)
      .withLogHandler(null)
      .start()
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    final Map<String, String> fieldsMap = new HashMap<>()

    span.log(expectedLogEvent)
    span.log(timeStamp, expectedLogEvent)
    span.log(fieldsMap)
    span.log(timeStamp, fieldsMap)
  }


  def "should delegate simple logs to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final Span span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    span.log(timeStamp, expectedLogEvent)

    expect:
    logHandler.assertLogCalledWithArgs(timeStamp, expectedLogEvent, span.delegate)
  }

  def "should delegate simple logs with timestamp to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final Span span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final String expectedLogEvent = "fakeEvent"
    span.log(expectedLogEvent)

    expect:
    logHandler.assertLogCalledWithArgs(expectedLogEvent, span.delegate)

  }

  def "should delegate logs with fields to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final Span span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final Map<String, String> fieldsMap = new HashMap<>()
    span.log(fieldsMap)

    expect:
    logHandler.assertLogCalledWithArgs(fieldsMap, span.delegate)

  }

  def "should delegate logs with fields and timestamp to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final Span span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final Map<String, String> fieldsMap = new HashMap<>()
    final timeStamp = System.currentTimeMillis()
    span.log(timeStamp, fieldsMap)

    expect:
    logHandler.assertLogCalledWithArgs(timeStamp, fieldsMap, span.delegate)

  }

  private static class TestLogHandler implements LogHandler {
    Object[] arguments = null

    @Override
    void log(Map<String, ?> fields, DDSpan span) {
      arguments = new Object[2]
      arguments[0] = fields
      arguments[1] = span
    }

    @Override
    void log(long timestampMicroseconds, Map<String, ?> fields, DDSpan span) {
      arguments = new Object[3]
      arguments[0] = timestampMicroseconds
      arguments[1] = fields
      arguments[2] = span
    }

    @Override
    void log(String event, DDSpan span) {
      arguments = new Object[2]
      arguments[0] = event
      arguments[1] = span
    }

    @Override
    void log(long timestampMicroseconds, String event, DDSpan span) {
      arguments = new Object[3]
      arguments[0] = timestampMicroseconds
      arguments[1] = event
      arguments[2] = span
    }

    boolean assertLogCalledWithArgs(Object... args) {
      if (arguments.size() != args.size()) {
        return false
      }
      for (int i = 0; i < args.size(); i++) {
        if (arguments[i] != args[i]) {
          return false
        }
      }
      return true
    }
  }
}
