package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.common.writer.ListWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How a span routes tags to its own known and custom stores: custom tags by name with every value
 * type, primitive sets of intercepted tags by id, map writes, and the custom store borrowed from
 * the tracer-level tags until the span's first custom write.
 */
class DDSpanContextStoresTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer =
        tracerBuilder()
            .writer(new ListWriter())
            .defaultSpanTags(Collections.singletonMap("global.custom", "from-tracer"))
            .build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  private DDSpan start() {
    return (DDSpan) tracer.startSpan("test-instr", "stores.op");
  }

  @Test
  void customTagsKeepEveryValueType() {
    DDSpan span = start();
    try {
      span.setTag("custom.chars", (CharSequence) "chars");
      span.setTag("custom.bool", true);
      span.setTag("custom.int", 7);
      span.setTag("custom.long", 8L);
      span.setTag("custom.float", 1.5f);
      span.setTag("custom.double", 2.5d);

      assertEquals("chars", span.getTag("custom.chars"));
      assertEquals(true, span.getTag("custom.bool"));
      assertEquals(7, span.getTag("custom.int"));
      assertEquals(8L, span.getTag("custom.long"));
      assertEquals(1.5f, span.getTag("custom.float"));
      assertEquals(2.5d, span.getTag("custom.double"));
    } finally {
      span.finish();
    }
  }

  @Test
  void interceptedTagsSetByIdAsPrimitivesAreRouted() {
    DDSpan span = start();
    try {
      span.spanContext().setTag(KnownTags.HTTP_STATUS_CODE_ID, 404);
      assertEquals(404, span.getHttpStatusCode());
      span.spanContext().setTag(KnownTags.HTTP_STATUS_CODE_ID, 503L);
      assertEquals(503, span.getHttpStatusCode());

      span.spanContext().setTag(KnownTags.DD_MEASURED_ID, true);
      assertTrue(span.isMeasured());

      span.spanContext().setTag(KnownTags.DD1_SR_EAUSR_ID, 0.25d);
      span.spanContext().setTag(KnownTags.DD1_SR_EAUSR_ID, 0.5f);
      assertEquals(0.5f, ((Number) span.getTag("_dd1.sr.eausr")).floatValue());
    } finally {
      span.finish();
    }
  }

  @Test
  void knownTagsSetByIdAsPrimitivesWithoutInterception() {
    DDSpan span = start();
    try {
      span.spanContext().setTag(KnownTags.PEER_PORT_ID, 5432L);
      span.spanContext().setTag(KnownTags.DB_INSTANCE_ID, 1.5f);
      span.spanContext().setTag(KnownTags.DB_POOL_NAME_ID, 2.5d);
      assertEquals(5432L, span.getTag("peer.port"));
      assertEquals(1.5f, span.getTag("db.instance"));
      assertEquals(2.5d, span.getTag("db.pool.name"));

      // an intercepted tag the interceptor declines is still stored: span.kind records its
      // ordinal and keeps the value
      span.spanContext().setTag(KnownTags.SPAN_KIND_ID, 1);
      assertEquals("1", String.valueOf(span.getTag("span.kind")));
    } finally {
      span.finish();
    }
  }

  @Test
  void metricsWithKnownNamesGoToTheKnownStore() {
    DDSpan span = start();
    try {
      span.setMetric("db.user", 200);
      span.setMetric("peer.port", 8080L);
      span.setMetric("db.instance", 0.5f);
      span.setMetric("db.pool.name", 0.75d);
      span.setMetric("custom.metric", 3L);
      assertEquals(200, span.getTag("db.user"));
      assertEquals(8080L, span.getTag("peer.port"));
      assertEquals(0.5f, span.getTag("db.instance"));
      assertEquals(0.75d, span.getTag("db.pool.name"));
      assertEquals(3L, span.getTag("custom.metric"));
    } finally {
      span.finish();
    }
  }

  @Test
  void aMapWriteRoutesKnownAndCustomTags() {
    DDSpan span = start();
    try {
      Map<String, Object> tags = new LinkedHashMap<>();
      tags.put("http.route", "/users");
      tags.put("custom.from.map", "value");
      tags.put("custom.chars.from.map", new StringBuilder("chars"));
      span.setAllTags(tags);
      span.spanContext().unsafeSetTag("custom.unsafe", (CharSequence) "unsafe");

      assertEquals("/users", span.getTag("http.route"));
      assertEquals("value", span.getTag("custom.from.map"));
      assertEquals("chars", String.valueOf(span.getTag("custom.chars.from.map")));
      assertEquals("unsafe", span.getTag("custom.unsafe"));
    } finally {
      span.finish();
    }
  }

  @Test
  void removingACustomTagTheSpanDoesNotHaveKeepsBorrowing() {
    DDSpan span = start();
    try {
      assertEquals("from-tracer", span.getTag("global.custom"));
      span.removeTag("never.set"); // nothing to hide: the borrowed store stays as it is
      assertEquals("from-tracer", span.getTag("global.custom"));
    } finally {
      span.finish();
    }
  }

  @Test
  void removingABorrowedCustomTagHidesItFromThisSpanOnly() {
    DDSpan span = start();
    DDSpan other = start();
    try {
      span.removeTag("global.custom"); // copies the borrowed store, then hides the tag
      assertNull(span.getTag("global.custom"));
      assertEquals("from-tracer", other.getTag("global.custom"), "the tracer-level tag stays");
    } finally {
      span.finish();
      other.finish();
    }
  }

  @Test
  void aPrototypesCustomTagIsSeededOnlyWhereAbsent() {
    SpanPrototype prototype =
        SpanPrototype.builder()
            .initInstrumentationName("test-instr")
            .initOperationName("stores.proto")
            .initTag("global.custom", "from-prototype")
            .initTag("proto.custom", "seeded")
            .build();
    DDSpan span = (DDSpan) tracer.startSpan(prototype, null);
    try {
      // the tracer-level tag is already visible, so the prototype's default doesn't replace it
      assertEquals("from-tracer", span.getTag("global.custom"));
      assertEquals("seeded", span.getTag("proto.custom"));
    } finally {
      span.finish();
    }
  }
}
