package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code SpanBuilder.withTag(long, ...)}: a builder tag set by id lands like one set by name. */
class CoreSpanBuilderWithTagByIdTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  @Test
  void aTagSetByIdIsStoredUnderItsName() {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "op")
            .withTag(KnownTags.HTTP_ROUTE_ID, "/users/{id}")
            .withTag(KnownTags.HTTP_STATUS_CODE_ID, 200)
            .withTag("my.custom.tag", "x")
            .start();

    assertEquals("/users/{id}", span.getTag(KnownTags.HTTP_ROUTE_NAME));
    assertEquals(200, span.getTag(KnownTags.HTTP_STATUS_CODE_NAME));
    assertEquals("x", span.getTag("my.custom.tag"));
    span.finish();
  }

  @Test
  void anInterceptedTagSetByIdIsRouted() {
    AgentSpan span =
        tracer.buildSpan("datadog", "op").withTag(KnownTags.RESOURCE_NAME_ID, "GET /x").start();

    assertEquals("GET /x", span.getResourceName().toString());
    span.finish();
  }

  @Test
  void aNullValueRemovesOnlyATagTheBuilderSet() {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "op")
            .withTag(KnownTags.HTTP_ROUTE_ID, "/users")
            .withTag(KnownTags.HTTP_ROUTE_ID, null)
            .withTag(KnownTags.HTTP_METHOD_ID, null)
            .start();

    assertNull(span.getTag(KnownTags.HTTP_ROUTE_NAME));
    span.finish();
  }

  @Test
  void anUnknownIdIsIgnored() {
    AgentSpan span =
        tracer.buildSpan("datadog", "op").withTag(KnownTagCodec.makeTagId(9999), "x").start();

    assertNull(span.getTag("x"));
    span.finish();
  }
}
