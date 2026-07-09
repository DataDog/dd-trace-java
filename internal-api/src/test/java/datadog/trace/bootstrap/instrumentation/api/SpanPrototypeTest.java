package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpanPrototypeTest {

  @Test
  void extendsInheritsBaseIdentityAndTagsThenOverrides() {
    final SpanPrototype base =
        SpanPrototype.builder()
            .instrumentationName("base")
            .spanType("base-type")
            .initKind("server")
            .build();
    final SpanPrototype derived =
        SpanPrototype.builder().extends_(base).initComponent("netty").spanType("http").build();

    assertEquals("base", derived.instrumentationName()); // inherited
    assertEquals("http", derived.spanType()); // overridden
    assertEquals("server", derived.tags().getString(Tags.SPAN_KIND)); // inherited tag
    assertEquals("netty", derived.tags().getString(Tags.COMPONENT)); // added tag
  }
}
