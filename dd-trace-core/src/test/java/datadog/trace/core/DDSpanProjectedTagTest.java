package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * The port concepts ({@code KnownTags.SERVER_PORT}, {@code KnownTags.CLIENT_PORT}), whose Datadog
 * name depends on the span's kind. Instrumentation writes the concept's id and the span names it
 * from its kind when it is written, exactly as the names were emitted before concepts. A write by
 * the Datadog name resolves with the span's kind, and before the kind is known stays a plain tag
 * that never duplicates the concept's name.
 */
class DDSpanProjectedTagTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  /** The names each id was emitted under before concepts, on spans of each kind. */
  @TableTest({
    "scenario           | kind     | concept     | name       ",
    "server's on client | client   | server.port | peer.port  ",
    "server's on prod.  | producer | server.port | peer.port  ",
    "server's on cons.  | consumer | server.port | peer.port  ",
    "server's on server | server   | server.port | server.port",
    "server's internal  | internal | server.port | peer.port  ",
    "client's on server | server   | client.port | peer.port  "
  })
  void aConceptWrittenByIdTakesItsNameFromTheSpanKind(String kind, String concept, String name) {
    DDSpan span = start(kind);
    long tagId = concept.equals("server.port") ? KnownTags.SERVER_PORT : KnownTags.CLIENT_PORT;

    span.setTag(tagId, 8080);

    assertEquals(singletonList(name), portNames(span));
    TagMap.Entry entry = span.getTags().getEntry(tagId);
    assertEquals(name, entry.tag());
    assertEquals(8080, span.getTag(name));
    span.finish();
  }

  @Test
  void aConceptWrittenByIdWithNoKindTakesTheNameOfASpanWithNoDirection() {
    DDSpan span = start(null);

    span.setTag(KnownTags.SERVER_PORT, 8080);

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(8080, span.getTag(Tags.PEER_PORT));
    span.finish();
  }

  @TableTest({
    "scenario      | kind     | concept    ",
    "on a server   | server   | client.port",
    "on a client   | client   | server.port",
    "on a consumer | consumer | server.port"
  })
  void aWriteByNameResolvesFromTheLiveSpansKind(String kind, String concept) {
    DDSpan span = start(kind);

    span.setTag(Tags.PEER_PORT, 5000);

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    long tagId = concept.equals("server.port") ? KnownTags.SERVER_PORT : KnownTags.CLIENT_PORT;
    assertEquals(5000, span.getTags().getEntry(tagId).objectValue());
    span.finish();
  }

  /** On a span with no direction, server.port written by name stays the name it was written as. */
  @Test
  void aWriteByNameOnASpanWithNoDirectionStaysAPlainTag() {
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
    span.setTag(KnownTags.SERVER_PORT, 2);

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(2, span.getTag(Tags.PEER_PORT));
    span.finish();
  }

  /** For now the concept wins over a name written later that still can't be resolved. */
  @Test
  void anUnresolvedNameWrittenAfterAConceptLosesToIt() {
    DDSpan span = start(null);

    span.setTag(KnownTags.SERVER_PORT, 2);
    span.setTag(Tags.PEER_PORT, 1);

    assertEquals(singletonList(Tags.PEER_PORT), portNames(span));
    assertEquals(2, span.getTag(Tags.PEER_PORT));
    span.finish();
  }

  @Test
  void removingAProjectedNameRemovesTheConcept() {
    DDSpan span = start(Tags.SPAN_KIND_CLIENT);
    span.setTag(KnownTags.SERVER_PORT, 443);

    span.setTag(Tags.PEER_PORT, (Object) null);

    assertEquals(emptyList(), portNames(span));
    span.finish();
  }

  @TableTest({
    "scenario           | kindFirst | kind   | concept    ",
    "kind before tag    | true      | server | client.port",
    "kind after tag     | false     | server | client.port",
    "client kind before | true      | client | server.port",
    "client kind after  | false     | client | server.port"
  })
  void aBuilderResolvesANameWithTheKindKnownAtBuildTime(
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
    long tagId = concept.equals("server.port") ? KnownTags.SERVER_PORT : KnownTags.CLIENT_PORT;
    assertEquals(80, span.getTags().getEntry(tagId).objectValue());
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

  /** Strict check, with assertions on as in tests: the kind can't change once a port is named. */
  @Test
  void theKindCannotChangeAfterAProjectedTagIsWritten() {
    boolean assertionsEnabled = false;
    assert assertionsEnabled = true;
    assumeTrue(assertionsEnabled, "the check is an assertion");
    DDSpan span = start(Tags.SPAN_KIND_CLIENT);
    span.setTag(KnownTags.SERVER_PORT, 443);

    assertThrows(AssertionError.class, () -> span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER));
    span.finish();
  }

  @Test
  @WithConfig(key = SPLIT_BY_TAGS, value = "peer.port")
  void splitByTagsMatchesTheNameAPortTakes() {
    CoreTracer splitting = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = (DDSpan) splitting.buildSpan("datadog", "op").start();
      span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

      span.setTag(KnownTags.SERVER_PORT, 5432);

      assertEquals("5432", span.getServiceName());
      span.finish();
    } finally {
      splitting.close();
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
