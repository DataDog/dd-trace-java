package datadog.opentracing

import datadog.trace.common.writer.ListWriter
import datadog.trace.test.util.DDSpecification
import io.opentracing.Span

class SpanBuilderTest extends DDSpecification {
  // TODO more io.opentracing.SpanBuilder specific tests

  def writer = new ListWriter()
  def tracer = DDTracer.builder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

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
}
