package datadog.opentracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpanBuilderTest extends DDJavaSpecification {
  // TODO more io.opentracing.SpanBuilder specific tests

  ListWriter writer = new ListWriter();
  DDTracer tracer = DDTracer.builder().writer(writer).build();

  @AfterEach
  void cleanup() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void shouldInheritDDParentAttributesAddReferenceChildOf() {
    String expectedName = "fakeName";
    String expectedParentServiceName = "fakeServiceName";
    String expectedParentResourceName = "fakeResourceName";
    String expectedParentType = "fakeType";
    String expectedChildServiceName = "fakeServiceName-child";
    String expectedChildResourceName = "fakeResourceName-child";
    String expectedChildType = "fakeType-child";
    String expectedBaggageItemKey = "fakeKey";
    String expectedBaggageItemValue = "fakeValue";

    Span parent =
        tracer
            .buildSpan(expectedName)
            .withServiceName("foo")
            .withResourceName(expectedParentResourceName)
            .withSpanType(expectedParentType)
            .start();

    parent.setBaggageItem(expectedBaggageItemKey, expectedBaggageItemValue);

    // ServiceName and SpanType are always set by the parent if they are not present in the child
    OTSpan span =
        (OTSpan)
            tracer
                .buildSpan(expectedName)
                .withServiceName(expectedParentServiceName)
                .addReference("child_of", parent.context())
                .start();

    assertEquals(expectedName, span.getDelegate().getOperationName());
    assertEquals(expectedBaggageItemValue, span.getBaggageItem(expectedBaggageItemKey));
    assertEquals(
        expectedParentServiceName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getServiceName());
    assertEquals(
        expectedName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getResourceName());
    assertNull(((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getSpanType());

    // ServiceName and SpanType are always overwritten by the child if they are present
    span =
        (OTSpan)
            tracer
                .buildSpan(expectedName)
                .withServiceName(expectedChildServiceName)
                .withResourceName(expectedChildResourceName)
                .withSpanType(expectedChildType)
                .addReference("child_of", parent.context())
                .start();

    assertEquals(expectedName, span.getDelegate().getOperationName());
    assertEquals(expectedBaggageItemValue, span.getBaggageItem(expectedBaggageItemKey));
    assertEquals(
        expectedChildServiceName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getServiceName());
    assertEquals(
        expectedChildResourceName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getResourceName());
    assertEquals(
        expectedChildType,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getSpanType());
  }

  @Test
  void shouldInheritDDParentAttributesAddReferenceFollowsFrom() {
    String expectedName = "fakeName";
    String expectedParentServiceName = "fakeServiceName";
    String expectedParentResourceName = "fakeResourceName";
    String expectedParentType = "fakeType";
    String expectedChildServiceName = "fakeServiceName-child";
    String expectedChildResourceName = "fakeResourceName-child";
    String expectedChildType = "fakeType-child";
    String expectedBaggageItemKey = "fakeKey";
    String expectedBaggageItemValue = "fakeValue";

    Span parent =
        tracer
            .buildSpan(expectedName)
            .withServiceName("foo")
            .withResourceName(expectedParentResourceName)
            .withSpanType(expectedParentType)
            .start();

    parent.setBaggageItem(expectedBaggageItemKey, expectedBaggageItemValue);

    // ServiceName and SpanType are always set by the parent if they are not present in the child
    OTSpan span =
        (OTSpan)
            tracer
                .buildSpan(expectedName)
                .withServiceName(expectedParentServiceName)
                .addReference("follows_from", parent.context())
                .start();

    assertEquals(expectedName, span.getDelegate().getOperationName());
    assertEquals(expectedBaggageItemValue, span.getBaggageItem(expectedBaggageItemKey));
    assertEquals(
        expectedParentServiceName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getServiceName());
    assertEquals(
        expectedName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getResourceName());
    assertNull(((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getSpanType());

    // ServiceName and SpanType are always overwritten by the child if they are present
    span =
        (OTSpan)
            tracer
                .buildSpan(expectedName)
                .withServiceName(expectedChildServiceName)
                .withResourceName(expectedChildResourceName)
                .withSpanType(expectedChildType)
                .addReference("follows_from", parent.context())
                .start();

    assertEquals(expectedName, span.getDelegate().getOperationName());
    assertEquals(expectedBaggageItemValue, span.getBaggageItem(expectedBaggageItemKey));
    assertEquals(
        expectedChildServiceName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getServiceName());
    assertEquals(
        expectedChildResourceName,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getResourceName());
    assertEquals(
        expectedChildType,
        ((DDSpanContext) ((OTSpanContext) span.context()).getDelegate()).getSpanType());
  }
}
