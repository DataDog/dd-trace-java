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
 * A few tag names mean different tags depending on the span's direction: {@code peer.port} is the
 * client's port on a server span and the server's on a client span, and {@code server.address} is
 * {@code http.hostname} inbound but {@code peer.hostname} outbound. A span resolves them by its
 * kind.
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
  void aSharedNameIsStoredAsTheTagOfTheSpansDirection() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_PORT, 5432);

    assertEquals(5432, storedValue(KnownTags.PEER_PORT_OUTBOUND_ID));
    assertNull(storedValue(KnownTags.PEER_PORT_INBOUND_ID));
    assertEquals(5432, span.getTag(Tags.PEER_PORT));
  }

  @Test
  void aSharedNameOnAServerSpanIsTheClientsPort() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.setTag(Tags.PEER_PORT, "1234");

    assertEquals("1234", storedValue(KnownTags.PEER_PORT_INBOUND_ID));
    assertEquals("1234", span.getTag(Tags.PEER_PORT));
  }

  @Test
  void aDirectionScopedOpenTelemetryNameIsStoredAsTheTagItDenotes() {
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
  void aNameSetBeforeTheSpanHasADirectionResolvesWhenItGetsOne() {
    span.setTag(Tags.PEER_PORT, 5432);
    span.setTag("server.address", "outbound.example");

    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    assertEquals(5432, storedValue(KnownTags.PEER_PORT_OUTBOUND_ID));
    assertEquals("outbound.example", span.getTag(Tags.PEER_HOSTNAME));
    assertNull(span.getTags().get("server.address"));
  }

  @Test
  void removingASharedNameRemovesTheTagOfTheSpansDirection() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_PORT, 5432);

    span.setTag(Tags.PEER_PORT, (String) null);

    assertNull(storedValue(KnownTags.PEER_PORT_OUTBOUND_ID));
    assertNull(span.getTag(Tags.PEER_PORT));
  }

  @Test
  void anInternalSpanHasNoPeerPort() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
    span.setTag(Tags.PEER_PORT, 5432);

    assertNull(storedValue(KnownTags.PEER_PORT_OUTBOUND_ID));
    assertNull(storedValue(KnownTags.PEER_PORT_INBOUND_ID));
    assertEquals(5432, span.getTag(Tags.PEER_PORT));
  }

  private Object storedValue(long tagId) {
    TagMap.Entry entry = span.getTags().getEntry(tagId);
    return entry == null ? null : entry.objectValue();
  }
}
