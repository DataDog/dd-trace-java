package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A few tag names depend on the span's direction. {@code peer.port} is one tag in every direction,
 * emitted as {@code client.port} inbound and {@code server.port} outbound. {@code server.address}
 * is {@code http.hostname} inbound but {@code peer.hostname} outbound: a value written under it is
 * stored as written, and read -- and, once the span is emitted, stored -- as the tag it denotes on
 * the span's kind.
 */
class DDSpanDirectionalTagsTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;
  private DDSpan span;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
    span = (DDSpan) tracer.buildSpan("datadog", "fakeOperation").start();
  }

  @AfterEach
  void tearDown() {
    span.finish();
    tracer.close();
  }

  @Test
  void aNameDeclaredPerDirectionIsOneTagInEveryDirection() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_PORT, 5432);

    assertEquals(5432, storedValue(KnownTags.PEER_PORT_ID));
    assertEquals(5432, span.getTag(Tags.PEER_PORT));
  }

  @Test
  void aNameDeclaredPerDirectionIsStoredBeforeTheSpanHasAKind() {
    span.setTag(Tags.PEER_PORT, "1234");

    assertEquals("1234", storedValue(KnownTags.PEER_PORT_ID));
    assertEquals("1234", span.getTag(Tags.PEER_PORT));
  }

  @Test
  void aDirectionScopedOpenTelemetryNameReadsAsTheTagItDenotes() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.setTag("server.address", "inbound.example");

    assertEquals("inbound.example", span.getTag(Tags.HTTP_HOSTNAME));
    assertEquals("inbound.example", span.getTag("server.address"));
    assertNull(span.getTag(Tags.PEER_HOSTNAME));
  }

  @Test
  void theSameOpenTelemetryNameIsADifferentTagOnAClientSpan() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag("server.address", "outbound.example");

    assertEquals("outbound.example", span.getTag(Tags.PEER_HOSTNAME));
    assertNull(span.getTag(Tags.HTTP_HOSTNAME));
  }

  @Test
  void aNameWithNoTagInTheSpansDirectionStaysACustomTag() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag("client.port", 1234);

    assertEquals(1234, span.getTag("client.port"));
    assertNull(span.getTag(Tags.PEER_PORT));
  }

  @Test
  void aNameSetBeforeTheSpanHasADirectionReadsAsTheTagOnceItHasOne() {
    span.setTag("server.address", "outbound.example");
    assertNull(span.getTag(Tags.PEER_HOSTNAME));

    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    assertEquals("outbound.example", span.getTag(Tags.PEER_HOSTNAME));
    assertEquals("outbound.example", span.getTag("server.address"));
  }

  @Test
  void theTagWinsOverItsDirectionScopedName() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_HOSTNAME, "tag.example");
    span.setTag("server.address", "scoped.example");

    assertEquals("tag.example", span.getTag(Tags.PEER_HOSTNAME));
    assertEquals("tag.example", span.getTag("server.address"));
  }

  @Test
  void removingADirectionScopedNameRemovesTheTagItDenotes() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_HOSTNAME, "tag.example");

    span.setTag("server.address", (String) null);

    assertNull(span.getTag(Tags.PEER_HOSTNAME));
  }

  @Test
  void removingATagRemovesAValueWrittenUnderItsDirectionScopedName() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag("server.address", "scoped.example");

    span.setTag(Tags.PEER_HOSTNAME, (String) null);

    assertNull(span.getTag(Tags.PEER_HOSTNAME));
    assertNull(span.getTag("server.address"));
  }

  @Test
  void removingANameDeclaredPerDirectionRemovesItsTag() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_PORT, 5432);

    span.setTag(Tags.PEER_PORT, (String) null);

    assertNull(storedValue(KnownTags.PEER_PORT_ID));
    assertNull(span.getTag(Tags.PEER_PORT));
  }

  @Test
  void anInternalSpanStoresPeerPortAsItsOneTag() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
    span.setTag(Tags.PEER_PORT, 5432);

    assertEquals(5432, storedValue(KnownTags.PEER_PORT_ID));
    assertEquals(5432, span.getTag(Tags.PEER_PORT));
  }

  private Object storedValue(long tagId) {
    TagMap.Entry entry = span.getTags().getEntry(tagId);
    return entry == null ? null : entry.objectValue();
  }
}
