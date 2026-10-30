package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTags;
import datadog.trace.api.IdStore;
import datadog.trace.api.KnownTags;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.FrozenTags;
import datadog.trace.bootstrap.instrumentation.api.TagContributor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

/**
 * {@link DDSpanFrozenTagsTest} with the dense store on, where frozen known tags that need no
 * interception and aren't already on the span are copied in at once ({@code
 * DDSpanContext.setTagsFrom(FrozenTags)}); runs in its own JVM because the flag is read once. Adds
 * which path each shape takes: the inherited tests pass on either, so these pin that the bulk copy
 * is actually used.
 */
@WithConfig(key = TracerConfig.TRACE_DENSE_TAGS_ENABLED, value = "true")
class DDSpanFrozenTagsDenseForkedTest extends DDSpanFrozenTagsTest {
  private static final FrozenTags PEER_TAGS =
      FrozenTags.of(
          (TagContributor)
              target -> {
                target.setTag(KnownTags.PEER_HOSTNAME_ID, "db.example");
                target.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                target.setTag(KnownTags.PEER_PORT_ID, 5432);
              });

  @Test
  void theDenseStoreIsOn() {
    // guards the rest of this class: with the flag missed, every test would quietly run on the
    // hash store, where the frozen tag tests pass too
    assertEquals("DenseIdStore", IdStore.create(null).getClass().getSimpleName());
  }

  @Test
  void peerTagsOnAFreshSpanGoInBulk() {
    DDSpan span = start();
    try {
      assertTrue(span.spanContext().setTagsFrom(PEER_TAGS));

      assertEquals("db.example", span.getTag(Tags.PEER_HOSTNAME));
      assertEquals("10.0.0.1", span.getTag(Tags.PEER_HOST_IPV4));
      assertEquals(5432, span.getTag(Tags.PEER_PORT));
    } finally {
      span.finish();
    }
  }

  @Test
  void spanKindStillGoesInBulk() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                target -> {
                  target.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
                  target.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                });
    DDSpan span = (DDSpan) tracer.buildSpan("test-instr", "frozen-tags.op").start();
    try {
      assertTrue(span.spanContext().setTagsFrom(frozen));
      assertEquals(Tags.SPAN_KIND_CLIENT, span.getSpanKindString()); // the bulk path sets it
    } finally {
      span.finish();
    }
  }

  @Test
  void anInterceptedTagSendsTheSetOneAtATime() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                target -> {
                  target.setTag(DDTags.RESOURCE_NAME, "frozen-resource");
                  target.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                });
    DDSpan span = start();
    try {
      assertFalse(span.spanContext().setTagsFrom(frozen));
      assertEquals("frozen-resource", span.getResourceName().toString());
    } finally {
      span.finish();
    }
  }

  @Test
  void aTagTheSpanAlreadyHasSendsTheSetOneAtATime() {
    DDSpan span = start();
    try {
      span.setTag(Tags.PEER_PORT, 1);
      assertFalse(span.spanContext().setTagsFrom(PEER_TAGS));
      assertEquals(5432, span.getTag(Tags.PEER_PORT)); // still overwritten
    } finally {
      span.finish();
    }
  }
}
