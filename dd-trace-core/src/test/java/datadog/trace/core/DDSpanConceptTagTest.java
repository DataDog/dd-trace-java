package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * The port concepts ({@code KnownTags.SERVER_PORT}, {@code PEER_SERVER_PORT}, {@code
 * PEER_CLIENT_PORT}): one id per meaning, each with fixed names, two of them sharing the Datadog
 * name {@code peer.port}. Instrumentation writes the id, which is never ambiguous. A write by the
 * shared name resolves with the span's kind, and before the kind is known stays a plain tag that
 * never duplicates a concept's name.
 */
class DDSpanConceptTagTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  /** An id's name never depends on the span. */
  @TableTest({
    "scenario                | kind     | concept          | name       ",
    "remote server on client | client   | peer.server.port | peer.port  ",
    "remote server, consumer | consumer | peer.server.port | peer.port  ",
    "remote server, no kind  |          | peer.server.port | peer.port  ",
    "own port on server      | server   | server.port      | server.port",
    "remote client on server | server   | peer.client.port | peer.port  "
  })
  void aConceptWrittenByIdKeepsItsName(String kind, String concept, String name) {
    DDSpan span = start(kind);
    long tagId = id(concept);

    span.setTag(tagId, 8080);

    assertEquals(singletonList(name), portNames(span));
    assertEquals(name, span.getTags().getEntry(tagId).tag());
    assertEquals(8080, span.getTag(name));
    span.finish();
  }

  @TableTest({
    "scenario      | kind     | concept         ",
    "on a server   | server   | peer.client.port",
    "on a client   | client   | peer.server.port",
    "on a producer | producer | peer.server.port",
    "on a consumer | consumer | peer.server.port"
  })
  void aWriteBySharedNameResolvesFromTheSpansKind(String kind, String concept) {
    DDSpan span = start(kind);

    span.setTag(Tags.PEER_PORT, 5000);

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(5000, span.getTags().getEntry(id(concept)).objectValue());
    span.finish();
  }

  /** On a span with no direction, a shared name written by name stays the plain tag it was. */
  @Test
  void aWriteBySharedNameOnASpanWithNoDirectionStaysAPlainTag() {
    DDSpan span = start(Tags.SPAN_KIND_INTERNAL);

    span.setTag("server.port", 8080);

    assertEquals(singletonList("server.port"), portNames(span));
    assertNull(span.getTags().getEntry(KnownTags.SERVER_PORT));
    span.finish();
  }

  @Test
  void aConceptReplacesAnUnresolvedNameWrittenBeforeTheKind() {
    DDSpan span = start(null);

    span.setTag(Tags.PEER_PORT, 1);
    span.setTag(KnownTags.PEER_SERVER_PORT, 2);

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(2, span.getTag(Tags.PEER_PORT));
    span.finish();
  }

  /** For now a concept wins over a shared name written later that still can't be resolved. */
  @Test
  void anUnresolvedNameWrittenAfterAConceptLosesToIt() {
    DDSpan span = start(null);

    span.setTag(KnownTags.PEER_SERVER_PORT, 2);
    span.setTag(Tags.PEER_PORT, 1);

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(2, span.getTag(Tags.PEER_PORT));
    span.finish();
  }

  @Test
  void removingASharedNameRemovesTheConcept() {
    DDSpan span = start(Tags.SPAN_KIND_CLIENT);
    span.setTag(KnownTags.PEER_SERVER_PORT, 443);

    span.setTag(Tags.PEER_PORT, (Object) null);

    assertEquals(emptyList(), portNames(span));
    span.finish();
  }

  @TableTest({
    "scenario           | kindFirst | kind   | concept         ",
    "kind before tag    | true      | server | peer.client.port",
    "kind after tag     | false     | server | peer.client.port",
    "client kind before | true      | client | peer.server.port",
    "client kind after  | false     | client | peer.server.port"
  })
  void aBuilderResolvesASharedNameWithTheKindKnownAtBuildTime(
      boolean kindFirst, String kind, String concept) {
    CoreTracer.CoreSpanBuilder builder = tracer.buildSpan("datadog", "op");
    if (kindFirst) {
      builder.withTag(Tags.SPAN_KIND, kind);
    }
    builder.withTag(Tags.PEER_PORT, 80);
    if (!kindFirst) {
      builder.withTag(Tags.SPAN_KIND, kind);
    }
    DDSpan span = (DDSpan) builder.start();

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(80, span.getTags().getEntry(id(concept)).objectValue());
    span.finish();
  }

  /** The builder's tags replay in order, so the last write wins exactly as before. */
  @Test
  void aBuilderKeepsLastWriteWins() {
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("datadog", "op")
                .withTag(Tags.PEER_PORT, 1)
                .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
                .withTag(Tags.PEER_PORT, 2)
                .start();

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(2, span.getTag(Tags.PEER_PORT));
    span.finish();
  }

  @Test
  @WithConfig(key = SPLIT_BY_TAGS, value = "peer.port")
  void splitByTagsOnASharedNameMatchesTheConceptsWithThatName() {
    CoreTracer splitting = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = (DDSpan) splitting.buildSpan("datadog", "op").start();
      span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

      span.setTag(KnownTags.PEER_SERVER_PORT, 5432);

      assertEquals("5432", span.getServiceName());
      span.finish();
    } finally {
      splitting.close();
    }
  }

  private static long id(String concept) {
    switch (concept) {
      case "server.port":
        return KnownTags.SERVER_PORT;
      case "peer.server.port":
        return KnownTags.PEER_SERVER_PORT;
      default:
        return KnownTags.PEER_CLIENT_PORT;
    }
  }

  private DDSpan start(String kind) {
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op").start();
    if (kind != null) {
      span.setTag(Tags.SPAN_KIND, kind);
    }
    return span;
  }

  /** The port names the span would emit: every stored tag named like a port. */
  private static List<String> portNames(DDSpan span) {
    List<String> names = new ArrayList<>();
    for (TagMap.EntryReader entry : span.getTags()) {
      String name = entry.tag();
      if (name.equals(Tags.PEER_PORT) || name.equals("server.port") || name.equals("client.port")) {
        names.add(name);
      }
    }
    return names;
  }
}
