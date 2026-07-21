package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SpanPrototypeTest {

  @Test
  void extendsInheritsBaseIdentityAndTagsThenOverrides() {
    final SpanPrototype base =
        SpanPrototype.builder()
            .initInstrumentationName("base")
            .initSpanType("base-type")
            .initKind("server")
            .build();
    final SpanPrototype derived =
        SpanPrototype.builder()
            .extends_(base)
            .initComponentOnly("netty")
            .initSpanType("http")
            .build();

    assertEquals("base", derived.instrumentationName()); // inherited
    assertEquals("http", derived.spanType()); // overridden
    assertEquals("server", derived.tags().getString(Tags.SPAN_KIND)); // inherited tag
    assertEquals("netty", derived.tags().getString(Tags.COMPONENT)); // added tag
  }

  @Test
  void emptyOrNullConstantsAreDroppedNotBaked() {
    // Match AgentSpan.setTag / the cached-Entry path: a null or empty constant is "no tag", not an
    // empty tag. A raw tags.set would otherwise bake a tag that per-span stamping never emits.
    final SpanPrototype proto =
        SpanPrototype.builder()
            .initComponentOnly("") // empty -> dropped
            .initKind("") // empty -> dropped
            .initTag("empty.cs", "") // empty CharSequence -> dropped
            .initTag("null.cs", (CharSequence) null) // null -> dropped
            .initTag("null.obj", (Object) null) // null -> dropped
            .initTag("kept", "v") // non-empty -> present
            .build();

    assertNull(proto.tags().getString(Tags.COMPONENT));
    assertNull(proto.tags().getString(Tags.SPAN_KIND));
    assertNull(proto.tags().getString("empty.cs"));
    assertNull(proto.tags().getString("null.cs"));
    assertNull(proto.tags().getString("null.obj"));
    assertEquals("v", proto.tags().getString("kept")); // sanity: non-empty still stored
  }
}
