package datadog.trace.core;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
