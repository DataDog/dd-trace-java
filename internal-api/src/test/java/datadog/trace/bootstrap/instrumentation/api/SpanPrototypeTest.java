package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMap;
import org.junit.jupiter.api.Test;

class SpanPrototypeTest {

  @Test
  void noneHasNoIdentityAndNoTags() {
    assertNull(SpanPrototype.NONE.instrumentationName());
    assertNull(SpanPrototype.NONE.operationName());
    assertNull(SpanPrototype.NONE.spanType());
    assertNull(SpanPrototype.NONE.integrationName());
    assertTrue(SpanPrototype.NONE.tags().isEmpty());
  }

  @Test
  void gettersReflectBuilderState() {
    final SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("instr")
            .initOperationName("op")
            .initSpanType("web")
            .initComponentAndIntegration("netty") // sets integration name + component tag
            .build();

    assertEquals("instr", proto.instrumentationName());
    assertEquals("op", proto.operationName());
    assertEquals("web", proto.spanType());
    assertEquals("netty", proto.integrationName());
    assertEquals("netty", proto.tags().getString(Tags.COMPONENT));
  }

  @Test
  void initInstrumentationNamesTakesFirstElement() {
    final SpanPrototype proto =
        SpanPrototype.builder().initInstrumentationNames(new String[] {"first", "second"}).build();

    assertEquals("first", proto.instrumentationName());
  }

  @Test
  void initInstrumentationNamesNullArrayLeavesNameUnset() {
    final SpanPrototype proto = SpanPrototype.builder().initInstrumentationNames(null).build();

    assertNull(proto.instrumentationName());
  }

  @Test
  void initInstrumentationNamesEmptyArrayLeavesNameUnset() {
    final SpanPrototype proto =
        SpanPrototype.builder().initInstrumentationNames(new String[0]).build();

    assertNull(proto.instrumentationName());
  }

  @Test
  void extendsNullBaseIsNoOp() {
    final SpanPrototype proto = SpanPrototype.builder().extends_(null).build();

    assertNull(proto.instrumentationName());
    assertNull(proto.operationName());
    assertNull(proto.spanType());
    assertNull(proto.integrationName());
    assertTrue(proto.tags().isEmpty());
  }

  @Test
  void extendsCopiesAllIdentityAndTags() {
    final SpanPrototype base =
        SpanPrototype.builder()
            .initInstrumentationName("base")
            .initOperationName("base.op")
            .initSpanType("base-type")
            .initComponentAndIntegration("base-comp") // integration name + component tag
            .initKind("server")
            .build();
    final SpanPrototype derived = SpanPrototype.builder().extends_(base).build();

    assertEquals("base", derived.instrumentationName());
    assertEquals("base.op", derived.operationName());
    assertEquals("base-type", derived.spanType());
    assertEquals("base-comp", derived.integrationName());
    assertEquals("base-comp", derived.tags().getString(Tags.COMPONENT));
    assertEquals("server", derived.tags().getString(Tags.SPAN_KIND));
  }

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
  void initComponentAndIntegrationSetsBothComponentTagAndIntegrationName() {
    final SpanPrototype proto =
        SpanPrototype.builder().initComponentAndIntegration("netty").build();

    assertEquals("netty", proto.tags().getString(Tags.COMPONENT));
    assertEquals("netty", proto.integrationName());
  }

  @Test
  void initComponentAndIntegrationEmptyIsNoOpForBoth() {
    final SpanPrototype proto = SpanPrototype.builder().initComponentAndIntegration("").build();

    assertNull(proto.tags().getString(Tags.COMPONENT));
    assertNull(proto.integrationName());
  }

  @Test
  void initKindSetsSpanKindTag() {
    final SpanPrototype proto = SpanPrototype.builder().initKind("client").build();

    assertEquals("client", proto.tags().getString(Tags.SPAN_KIND));
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

  @Test
  void initTagObjectStoresNonEmptyValue() {
    final SpanPrototype proto = SpanPrototype.builder().initTag("count", (Object) 42).build();

    assertEquals(42, proto.tags().get("count"));
  }

  @Test
  void initTagEntryReaderStoresEntry() {
    final TagMap.EntryReader entry = TagMap.Entry.create("cached", "value");
    final SpanPrototype proto = SpanPrototype.builder().initTag(entry).build();

    assertEquals("value", proto.tags().getString("cached"));
  }

  @Test
  void initTagNullEntryReaderIsNoOp() {
    final SpanPrototype proto = SpanPrototype.builder().initTag((TagMap.EntryReader) null).build();

    assertTrue(proto.tags().isEmpty());
  }
}
