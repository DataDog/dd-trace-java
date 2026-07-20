package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.common.writer.ListWriter;
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
            .instrumentationName("test-instr")
            .operationName("proto.op")
            .spanType("web")
            .initKind(SPAN_KIND_SERVER)
            .initComponent("test-component")
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
  void explicitBuilderTagOverridesPrototypeConstant() {
    // prototype seeds `component` just before the builder's own tags, so the explicit withTag wins
    DDSpan span = (DDSpan) tracer.buildSpan(prototype, null).withTag(COMPONENT, "override").start();
    try {
      assertEquals("override", span.getTags().get(COMPONENT));
    } finally {
      span.finish();
    }
  }
}
