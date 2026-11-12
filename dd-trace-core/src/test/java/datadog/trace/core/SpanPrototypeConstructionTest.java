package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.common.writer.ListWriter;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the SpanPrototype construction path: {@code buildSpan(prototype, operationName)} and
 * {@code startSpan(prototype, operationName)} seed the prototype's identity + constant tags, with
 * the explicit operationName / builder tags overriding the prototype's (prototype = defaults).
 */
public class SpanPrototypeConstructionTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;
  private SpanPrototype prototype;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
    prototype =
        SpanPrototype.builder()
            .initInstrumentationName("test-instr")
            .initOperationName("proto.op")
            .initSpanType("web")
            .initKind(SPAN_KIND_SERVER)
            .initComponentOnly("test-component")
            .build();
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @Test
  void buildSpanSeedsPrototypeAndFallsBackToPrototypeOperationName() {
    DDSpan span = (DDSpan) tracer.buildSpan(prototype, null).start();
    try {
      assertEquals("proto.op", span.getOperationName().toString()); // null -> prototype's
      assertEquals("web", span.getSpanType());
      assertEquals("test-component", span.getTags().get(COMPONENT)); // constant tag seeded
    } finally {
      span.finish();
    }
  }

  @Test
  void seedsSpanKindOrdinalAndTag() {
    // span.kind is intercepted (its ordinal drives isOutbound). The prototype's tags seed through
    // the interceptor, so BOTH the ordinal side-effect and the span.kind tag Entry must land.
    DDSpan span = (DDSpan) tracer.buildSpan(prototype, null).start();
    try {
      assertEquals(SPAN_KIND_SERVER, span.getSpanKindString()); // ordinal side-effect applied
      assertEquals(SPAN_KIND_SERVER, span.getTags().get(SPAN_KIND)); // tag (shared Entry) present
    } finally {
      span.finish();
    }
  }

  @Test
  void explicitOperationNameOverridesPrototype() {
    DDSpan span = (DDSpan) tracer.buildSpan(prototype, "explicit.op").start();
    try {
      assertEquals("explicit.op", span.getOperationName().toString()); // explicit wins
    } finally {
      span.finish();
    }
  }

  @Test
  void startSpanSeedsPrototype() {
    DDSpan span = (DDSpan) tracer.startSpan(prototype, null);
    try {
      assertEquals("proto.op", span.getOperationName().toString());
      assertEquals("test-component", span.getTags().get(COMPONENT));
    } finally {
      span.finish();
    }
  }

  @Test
  void singleSpanBuilderSeedsPrototype() {
    DDSpan span = (DDSpan) tracer.singleSpanBuilder(prototype, null).start();
    try {
      assertEquals("proto.op", span.getOperationName().toString());
      assertEquals("web", span.getSpanType());
      assertEquals("test-component", span.getTags().get(COMPONENT));
      assertEquals(SPAN_KIND_SERVER, span.getSpanKindString());
    } finally {
      span.finish();
    }
  }

  @Test
  void aReusedSingleSpanBuilderDropsThePrototype() {
    // the thread's reusable builder carried a prototype for the first span; reset must clear it
    tracer.singleSpanBuilder(prototype, null).start().finish();
    DDSpan plain = (DDSpan) tracer.singleSpanBuilder("other-instr", "plain.op").start();
    try {
      assertNull(plain.getTags().get(COMPONENT));
      assertNull(plain.getSpanType());
    } finally {
      plain.finish();
    }
  }

  @Test
  void applyOverwritingAfterStartStampsThePrototype() {
    // the decorator path: start by name, then afterStart stamps the prototype over the span
    DDSpan span = (DDSpan) tracer.startSpan("test-instr", "decorated.op");
    try {
      span.applyOverwriting(prototype);
      assertEquals("web", span.getSpanType());
      assertEquals("test-component", span.getTags().get(COMPONENT));
      assertEquals(SPAN_KIND_SERVER, span.getTags().get(SPAN_KIND));
      assertEquals(SPAN_KIND_SERVER, span.getSpanKindString());
    } finally {
      span.finish();
    }
  }

  @Test
  void applyOverwritingReplacesATagTheSpanAlreadyHas() {
    DDSpan span = (DDSpan) tracer.startSpan("test-instr", "decorated.op");
    try {
      span.setTag(COMPONENT, "earlier");
      span.applyOverwriting(prototype);
      assertEquals("test-component", span.getTags().get(COMPONENT)); // the prototype wins
      assertEquals(SPAN_KIND_SERVER, span.getSpanKindString());
    } finally {
      span.finish();
    }
  }

  @Test
  void theSeedPlanTriesTheBulkPathOnlyWhenTheStoreSupportsIt() throws Exception {
    // With the hash store (the default) the plan must never be bulk: applyOverwriting would take
    // the tag lock for a bulk attempt that always fails, then again for its fallback.
    tracer.buildSpan(prototype, null).start().finish();
    Object plan = prototype.seedPlan();
    Field bulk = plan.getClass().getDeclaredField("bulk");
    bulk.setAccessible(true);
    assertEquals(prototype.tags().knownStore().supportsBulkSeed(), bulk.getBoolean(plan));
  }

  @Test
  void aPrototypesCustomTagIsSeededAndOverwritten() {
    SpanPrototype withCustom =
        SpanPrototype.builder()
            .initInstrumentationName("test-instr")
            .initOperationName("proto.op")
            .initKind(SPAN_KIND_SERVER)
            .initTag("my.custom.constant", "custom-value")
            .build();
    DDSpan built = (DDSpan) tracer.buildSpan(withCustom, null).start();
    try {
      assertEquals("custom-value", built.getTags().get("my.custom.constant"));
      assertEquals(SPAN_KIND_SERVER, built.getSpanKindString());
    } finally {
      built.finish();
    }

    DDSpan decorated = (DDSpan) tracer.startSpan("test-instr", "decorated.op");
    try {
      decorated.setTag("my.custom.constant", "earlier");
      decorated.applyOverwriting(withCustom);
      assertEquals("custom-value", decorated.getTags().get("my.custom.constant"));
    } finally {
      decorated.finish();
    }
  }

  @Test
  void explicitBuilderTagOverridesPrototypeConstant() {
    // prototype seeds `component` just before the builder's own tags, so the explicit withTag wins
    DDSpan span = (DDSpan) tracer.buildSpan(prototype, null).withTag(COMPONENT, "override").start();
    try {
      assertEquals("override", span.getTags().get(COMPONENT));
    } finally {
      span.finish();
    }
  }

  @Test
  void initComponentAndIntegrationSetsIntegrationName() {
    // Mirrors BaseDecorator.afterStart: the component tag is seeded AND the integration name is set
    // on the context, which IntegrationAdder serializes as _dd.integration (field -> tag mapping is
    // covered by IntegrationAdderTest).
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test-instr")
            .initComponentAndIntegration("netty")
            .build();
    DDSpan span = (DDSpan) tracer.buildSpan(proto, "op").start();
    try {
      assertEquals("netty", span.getTags().get(COMPONENT)); // component tag seeded
      assertEquals("netty", ((DDSpanContext) span.spanContext()).getIntegrationName());
    } finally {
      span.finish();
    }
  }

  @Test
  void initComponentOnlyDoesNotSetIntegrationName() {
    // initComponentOnly is tag-only: no integration-name side effect, so no _dd.integration.
    SpanPrototype proto =
        SpanPrototype.builder()
            .initInstrumentationName("test-instr")
            .initComponentOnly("netty")
            .build();
    DDSpan span = (DDSpan) tracer.buildSpan(proto, "op").start();
    try {
      assertEquals("netty", span.getTags().get(COMPONENT)); // tag present
      assertNull(((DDSpanContext) span.spanContext()).getIntegrationName()); // but no integration
    } finally {
      span.finish();
    }
  }

  @Test
  void extendsWithComponentOnlyOverrideLeavesInheritedIntegrationName() {
    // Documents a known desync: overriding an inherited initComponentAndIntegration component with
    // tag-only initComponentOnly does NOT clear the inherited integration name. Use
    // initComponentAndIntegration to override both together.
    SpanPrototype base =
        SpanPrototype.builder()
            .initInstrumentationName("test-instr")
            .initComponentAndIntegration("netty")
            .build();
    SpanPrototype derived =
        SpanPrototype.builder().extends_(base).initComponentOnly("other").build();
    DDSpan span = (DDSpan) tracer.buildSpan(derived, "op").start();
    try {
      assertEquals("other", span.getTags().get(COMPONENT)); // component overridden
      // integration name stays inherited from the base (the documented desync)
      assertEquals("netty", ((DDSpanContext) span.spanContext()).getIntegrationName());
    } finally {
      span.finish();
    }
  }
}
