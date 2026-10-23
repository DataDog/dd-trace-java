package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.IdStore;
import datadog.trace.api.SizingHint;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

/**
 * {@link SpanPrototypeConstructionTest} with the dense store on, where a prototype's known tags are
 * seeded in bulk ({@code DDSpanContext.seedPrototypeTags}); runs in its own JVM because the flag is
 * read once. Adds the bulk path's fallback: a span that already holds one of the prototype's tags
 * is seeded tag by tag, so its own value wins and the rest still land.
 */
@WithConfig(key = TracerConfig.TRACE_DENSE_TAGS_ENABLED, value = "true")
class SpanPrototypeConstructionDenseForkedTest extends SpanPrototypeConstructionTest {

  @Test
  void theDenseStoreIsOn() {
    // guards the rest of this class: with the flag missed, every test would quietly run on the
    // hash store, where the prototype tests pass too
    assertEquals("DenseIdStore", IdStore.create(null).getClass().getSimpleName());
  }

  @Test
  void aTagTheSpanAlreadyHasKeepsItsValueAndTheRestAreSeeded() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SpanPrototype prototype =
          SpanPrototype.builder()
              .initInstrumentationName("test-instr")
              .initOperationName("proto.op")
              .initKind(SPAN_KIND_CLIENT)
              .initComponentOnly("prototype-component")
              .build();
      DDSpan span = (DDSpan) tracer.buildSpan("test-instr", "op").start();
      try {
        span.setTag(COMPONENT, "explicit");
        span.spanContext().apply(prototype);

        assertEquals("explicit", span.getTags().get(COMPONENT)); // present: kept
        assertEquals(SPAN_KIND_CLIENT, span.getTags().get(SPAN_KIND)); // absent: seeded
        assertEquals(SPAN_KIND_CLIENT, span.getSpanKindString()); // and its ordinal
      } finally {
        span.finish();
      }
    } finally {
      tracer.close();
    }
  }

  @Test
  void aPrototypeCachesTheSizingHintForItsOwnOperationNameOnly() {
    SpanPrototype prototype =
        SpanPrototype.builder()
            .initInstrumentationName("test-instr")
            .initOperationName("sizing.proto.op")
            .build();
    SizingHint own = prototype.sizingHint(true, "sizing.proto.op");
    assertNotNull(own);
    assertSame(SizingHint.forOperation("sizing.proto.op", true), own, "keyed like the table");
    assertSame(own, prototype.sizingHint(true, UTF8BytesString.create("sizing.proto.op")));

    // another operation name gets that name's hint, never the prototype's
    SizingHint other = prototype.sizingHint(true, "sizing.proto.other");
    assertSame(SizingHint.forOperation("sizing.proto.other", true), other);
    assertNotSame(own, other);

    // the entry and child lanes stay apart
    assertNotSame(own, prototype.sizingHint(false, "sizing.proto.op"));
  }

  @Test
  void aPrototypeSeedsEverySpanBuiltFromIt() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SpanPrototype prototype =
          SpanPrototype.builder()
              .initInstrumentationName("test-instr")
              .initOperationName("proto.op")
              .initKind(SPAN_KIND_SERVER)
              .initComponentOnly("prototype-component")
              .build();
      for (int i = 0; i < 3; i++) { // the first span builds the cached plan, the rest reuse it
        DDSpan span = (DDSpan) tracer.buildSpan(prototype, null).start();
        try {
          assertEquals("prototype-component", span.getTags().get(COMPONENT));
          assertEquals(SPAN_KIND_SERVER, span.getSpanKindString());
        } finally {
          span.finish();
        }
      }
    } finally {
      tracer.close();
    }
  }
}
