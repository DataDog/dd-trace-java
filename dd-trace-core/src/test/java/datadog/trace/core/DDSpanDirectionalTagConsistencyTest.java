package datadog.trace.core;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * A direction-dependent name ({@code peer.port}, {@code server.address}) must hold one value per
 * span, whichever path writes it and whenever the span learns its kind.
 */
class DDSpanDirectionalTagConsistencyTest extends DDCoreJavaSpecification {
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
  void aBulkWriteReplacesTheValueOfASharedName() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_PORT, 80);

    span.setAllTags(singletonMap(Tags.PEER_PORT, 81));

    assertEquals(81, span.getTag(Tags.PEER_PORT));
    assertEquals(1, entriesNamed(span.getTags(), Tags.PEER_PORT));
  }

  /**
   * The alias filter passes some custom names that aren't aliases. Seeding one must not mark the
   * span as holding a pending alias, which would keep its aliased-tag writes and its serialization
   * on the slow path for the span's whole life.
   */
  @Test
  void aCustomTagThatOnlyPassesTheAliasFilterLeavesTheSpanOnTheFastPath() {
    String lookalike = "custom.tag18";
    assertTrue(KnownTagCodec.mayBeScopedAlias(lookalike), "precondition: a filter false positive");

    DDSpan built =
        (DDSpan)
            tracer
                .buildSpan("datadog", "built")
                .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
                .withTag(lookalike, "value")
                .start();
    try {
      assertFalse(built.spanContext().hasPendingAliases());
      assertEquals("value", built.getTag(lookalike));
    } finally {
      built.finish();
    }
  }

  @Test
  void aRealAliasWrittenBeforeTheKindIsKnownMarksTheSpan() {
    DDSpan built =
        (DDSpan) tracer.buildSpan("datadog", "built").withTag("server.address", "host").start();
    try {
      assertTrue(built.spanContext().hasPendingAliases());
    } finally {
      built.finish();
    }
  }

  /** A removal before the span has a kind must not let an older value resurface once it does. */
  @Test
  void removingAnAliasBeforeTheKindIsKnownSticksOnceTheKindArrives() {
    span.setTag(Tags.PEER_HOSTNAME, "older");
    span.setTag("server.address", (Object) null);

    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    assertNull(span.getTag(Tags.PEER_HOSTNAME));
    assertNull(span.getTag("server.address"));
  }

  @Test
  void removingATagBeforeTheKindIsKnownDropsAPendingAliasForIt() {
    span.setTag("server.address", "pending");
    span.setTag(Tags.PEER_HOSTNAME, (Object) null);

    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    assertNull(span.getTag(Tags.PEER_HOSTNAME));
  }

  /**
   * A pending alias is folded into its tag as soon as the span learns its kind, not at
   * serialization, so readers that run before then (peer service naming, stats) see the tag.
   */
  @Test
  void aPendingAliasIsResolvedAsSoonAsTheKindIsKnown() {
    span.setTag("server.address", "db.example.com");

    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    TagMap stored = span.getTags();
    assertEquals("db.example.com", stored.getObject(Tags.PEER_HOSTNAME));
    assertNull(stored.getObject("server.address"));
  }

  /** A prototype's alias default must not overwrite an explicit value of the tag it denotes. */
  @Test
  void aPrototypeAliasDefaultDoesNotClobberAnExplicitTag() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_HOSTNAME, "explicit");

    span.apply(SpanPrototype.builder().initTag("server.address", "from-prototype").build());

    assertEquals("explicit", span.getTag(Tags.PEER_HOSTNAME));
  }

  /** A builder removal of an alias also removes the tag a direction-aware write stored it as. */
  @Test
  void aBuilderRemovalOfAnAliasRemovesTheTagItWasStoredAs() {
    DDSpan built =
        (DDSpan)
            tracer
                .buildSpan("datadog", "built")
                .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
                .withTag("server.address", "host")
                .withTag("server.address", (Object) null)
                .start();
    try {
      assertNull(built.getTag(Tags.PEER_HOSTNAME));
      assertNull(built.getTags().getObject(Tags.PEER_HOSTNAME));
    } finally {
      built.finish();
    }
  }

  @Test
  void aBuilderTagWithASharedNameIsAKnownTag() {
    DDSpan built =
        (DDSpan)
            tracer
                .buildSpan("datadog", "built")
                .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
                .withTag(Tags.PEER_PORT, 5432)
                .start();
    try {
      assertEquals(5432, built.getTag(Tags.PEER_PORT));
      assertNotEquals(0L, tagIdOf(built.getTags(), Tags.PEER_PORT));
    } finally {
      built.finish();
    }
  }

  @Test
  void aLaterCanonicalWriteWinsOverAnEarlierAlias() {
    span.setTag("server.address", "old.example");
    span.setTag(Tags.PEER_HOSTNAME, "new.example");

    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    assertEquals("new.example", span.getTag(Tags.PEER_HOSTNAME));
  }

  @TableTest({
    "scenario                         | kindFirst | kind   | direction | first          | second        ",
    "client: tag then alias, pre-kind | false     | client | 1         | peer.hostname  | server.address",
    "client: alias then tag, pre-kind | false     | client | 1         | server.address | peer.hostname ",
    "client: alias overwrites tag     | true      | client | 1         | peer.hostname  | server.address",
    "client: tag overwrites alias     | true      | client | 1         | server.address | peer.hostname ",
    "server: tag then alias, pre-kind | false     | server | 0         | http.hostname  | server.address",
    "server: alias then tag, pre-kind | false     | server | 0         | server.address | http.hostname ",
    "server: alias overwrites tag     | true      | server | 0         | http.hostname  | server.address",
    "server: tag overwrites alias     | true      | server | 0         | server.address | http.hostname "
  })
  void theLastWriteWinsBetweenATagAndItsAlias(
      boolean kindFirst, String kind, int direction, String first, String second) {
    String tag = first.equals("server.address") ? second : first;
    if (kindFirst) {
      span.setTag(Tags.SPAN_KIND, kind);
    }
    span.setTag(first, "first.example");
    span.setTag(second, "second.example");
    if (!kindFirst) {
      span.setTag(Tags.SPAN_KIND, kind);
    }

    assertEquals("second.example", span.getTag(tag));
    assertEquals("second.example", span.getTag("server.address"));

    // And when the span is emitted: the pending alias, if any, is already folded into the tag.
    TagMap emitted = span.getTags();
    assertEquals("second.example", emitted.getObject(tag));
    assertNull(emitted.getObject("server.address"));
  }

  /**
   * The accepted corner (see DirectionalTags): a pre-kind write of either possible tag drops the
   * alias.
   */
  @Test
  void aPreKindWriteOfTheOtherPossibleTagDropsAPendingAlias() {
    span.setTag("server.address", "scoped.example");
    span.setTag(Tags.PEER_HOSTNAME, "peer.example");
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);

    assertNull(span.getTag(Tags.HTTP_HOSTNAME));
    assertEquals("peer.example", span.getTag(Tags.PEER_HOSTNAME));
  }

  @Test
  void aSharedNameKeepsItsValueWhenTheKindChanges() {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.PEER_PORT, 5432);

    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);

    assertEquals(5432, span.getTag(Tags.PEER_PORT));
    assertEquals(1, entriesNamed(span.getTags(), Tags.PEER_PORT));
  }

  @Test
  void anAliasWrittenWhileAnotherThreadSetsTheKindStillResolves() throws Exception {
    // A race window, not a deterministic failure: repeat to make losing it likely.
    for (int i = 0; i < 500; i++) {
      DDSpan raced = (DDSpan) tracer.buildSpan("datadog", "raced").start();
      CountDownLatch start = new CountDownLatch(1);
      Thread kindSetter =
          new Thread(
              () -> {
                awaitQuietly(start);
                raced.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
              });
      kindSetter.start();
      start.countDown();
      raced.setTag("server.address", "raced.example");
      kindSetter.join(TimeUnit.SECONDS.toMillis(5));

      assertEquals("raced.example", raced.getTag(Tags.PEER_HOSTNAME), "iteration " + i);
      raced.finish();
    }
  }

  private static int entriesNamed(TagMap tags, String name) {
    int count = 0;
    for (TagMap.EntryReader entry : tags) {
      if (name.equals(entry.tag())) {
        count++;
      }
    }
    return count;
  }

  private static long tagIdOf(TagMap tags, String name) {
    for (TagMap.EntryReader entry : tags) {
      if (name.equals(entry.tag())) {
        return entry.tagId();
      }
    }
    return 0L;
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
