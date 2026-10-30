package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.FrozenTags;
import datadog.trace.bootstrap.instrumentation.api.SpanData;
import datadog.trace.bootstrap.instrumentation.api.TagContributor;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code DDSpan.setTagsFrom(FrozenTags)}: applying recorded tags is equivalent to making the
 * recorded writes on the span. {@link DDSpanFrozenTagsDenseForkedTest} reruns these with the dense
 * store, where the known tags can be copied in at once.
 */
class DDSpanFrozenTagsTest extends DDCoreJavaSpecification {
  CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  DDSpan start() {
    return (DDSpan) tracer.startSpan("test-instr", "frozen-tags.op");
  }

  /**
   * A non-String value, shared so both spans hold the same object (StringBuilder has no equals).
   */
  private static final Object OBJECT_VALUE = new StringBuilder("built");

  /** Known tags by id and by name, custom tags, every value type, and a set-then-removed tag. */
  private static void writeTags(SpanData span) {
    span.setTag(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    span.setTag(KnownTags.HTTP_STATUS_CODE_ID, 503);
    span.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
    span.setTag(Tags.PEER_PORT, 5432);
    span.setTag("custom.string", "value");
    span.setTag("custom.object", OBJECT_VALUE);
    span.setTag("custom.bool", true);
    span.setTag("custom.long", 8L);
    span.setTag("custom.double", 2.5d);
    span.setTag("custom.removed", "gone");
    span.setTag("custom.removed", (Object) null);
  }

  @Test
  void frozenTagsSetWhatTheRecordedWritesWouldSet() {
    FrozenTags frozen = FrozenTags.of((TagContributor) DDSpanFrozenTagsTest::writeTags);
    DDSpan live = start();
    DDSpan replayed = start();
    try {
      writeTags(live);
      assertSame(replayed, replayed.setTagsFrom(frozen));

      // compared as plain maps: TagMap.equals isn't Map content equality
      assertEquals(new TreeMap<>(live.getTags()), new TreeMap<>(replayed.getTags()));
    } finally {
      live.finish();
      replayed.finish();
    }
  }

  @Test
  void everySpanGetsTheSameValueObjects() {
    // the point of freezing: a value built once is shared, not rebuilt per span
    TagExtractor<Integer> extractor =
        (n, target) -> target.setTag(KnownTags.PEER_IPV4_ID, "10.0.0." + n);
    FrozenTags frozen = FrozenTags.of(7, extractor);
    DDSpan first = start();
    DDSpan second = start();
    try {
      first.setTagsFrom(frozen);
      second.setTagsFrom(frozen);

      assertEquals("10.0.0.7", first.getTag(Tags.PEER_HOST_IPV4));
      assertSame(first.getTag(Tags.PEER_HOST_IPV4), second.getTag(Tags.PEER_HOST_IPV4));
    } finally {
      first.finish();
      second.finish();
    }
  }

  @Test
  void interceptedTagsKeepTheirEffects() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                target -> {
                  target.setTag(DDTags.RESOURCE_NAME, "frozen-resource"); // becomes the resource
                  target.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT); // sets the cached kind
                  target.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                });
    DDSpan span = start();
    try {
      span.setTagsFrom(frozen);

      assertEquals("frozen-resource", span.getResourceName().toString());
      assertEquals(Tags.SPAN_KIND_CLIENT, span.getSpanKindString());
      assertEquals("10.0.0.1", span.getTag(Tags.PEER_HOST_IPV4));
    } finally {
      span.finish();
    }
  }

  @Test
  void spanKindAloneIsAppliedWithItsOrdinal() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                target -> {
                  target.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CONSUMER);
                  target.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                });
    DDSpan span = start();
    try {
      span.setTagsFrom(frozen);

      assertEquals(Tags.SPAN_KIND_CONSUMER, span.getTag(Tags.SPAN_KIND));
      assertEquals(Tags.SPAN_KIND_CONSUMER, span.getSpanKindString());
    } finally {
      span.finish();
    }
  }

  @Test
  void frozenTagsOverwriteWhatTheSpanAlreadyHas() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                target -> {
                  target.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                  target.setTag(Tags.PEER_PORT, 5432);
                  target.setTag("custom.string", "frozen");
                });
    DDSpan span = start();
    try {
      span.setTag(Tags.PEER_HOST_IPV4, "192.168.0.1");
      span.setTag("custom.string", "explicit");
      span.setTag(Tags.COMPONENT, "untouched");
      span.setTagsFrom(frozen);

      assertEquals("10.0.0.1", span.getTag(Tags.PEER_HOST_IPV4));
      assertEquals(5432, span.getTag(Tags.PEER_PORT));
      assertEquals("frozen", span.getTag("custom.string"));
      assertEquals("untouched", span.getTag(Tags.COMPONENT));
    } finally {
      span.finish();
    }
  }

  @Test
  void frozenTagsApplyAcrossTracers() {
    // the core's apply plan is cached per interceptor; a second tracer must not reuse the first's
    FrozenTags frozen = FrozenTags.of((TagContributor) DDSpanFrozenTagsTest::writeTags);
    CoreTracer other = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan first = start();
      DDSpan second = (DDSpan) other.startSpan("test-instr", "frozen-tags.op");
      try {
        first.setTagsFrom(frozen);
        second.setTagsFrom(frozen);

        assertEquals(new TreeMap<>(first.getTags()), new TreeMap<>(second.getTags()));
      } finally {
        first.finish();
        second.finish();
      }
    } finally {
      other.close();
    }
  }

  @Test
  void emptyOrNullFrozenTagsChangeNothing() {
    DDSpan span = start();
    try {
      int before = span.getTags().size();
      assertSame(span, span.setTagsFrom(FrozenTags.EMPTY));
      assertSame(span, span.setTagsFrom((FrozenTags) null));
      assertEquals(before, span.getTags().size());
    } finally {
      span.finish();
    }
  }
}
