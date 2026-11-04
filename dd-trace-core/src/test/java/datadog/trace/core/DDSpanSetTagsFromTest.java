package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.SpanData;
import datadog.trace.bootstrap.instrumentation.api.TagContributor;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code DDSpan.setTagsFrom}: the contributor or extractor runs holding the span's tag lock once,
 * through a short-lived {@link SpanData} that keeps the span's tag semantics.
 */
class DDSpanSetTagsFromTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  private DDSpan start() {
    return (DDSpan) tracer.startSpan("test-instr", "tags-from.op");
  }

  /**
   * A non-String value, shared so both spans hold the same object (StringBuilder has no equals).
   */
  private static final Object OBJECT_VALUE = new StringBuilder("built");

  /** The same writes either way: known tags by id and by name, custom tags, every value type. */
  private static void writeTags(SpanData span) {
    span.setTag(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    span.setTag(KnownTags.HTTP_STATUS_CODE_ID, 503);
    span.setTag(Tags.COMPONENT, "jdbc");
    span.setTag("custom.string", "value");
    span.setTag("custom.object", OBJECT_VALUE);
    span.setTag("custom.bool", true);
    span.setTag("custom.int", 7);
    span.setTag("custom.long", 8L);
    span.setTag("custom.float", 1.5f);
    span.setTag("custom.double", 2.5d);
    span.setTag("custom.removed", "gone");
    span.setTag("custom.removed", (Object) null);
  }

  @Test
  void aContributorSetsWhatTheSameSetTagCallsWouldSet() {
    DDSpan oneByOne = start();
    DDSpan batched = start();
    try {
      writeTags(oneByOne);
      TagContributor contributor = DDSpanSetTagsFromTest::writeTags;
      assertSame(batched, batched.setTagsFrom(contributor));

      // compared as plain maps: TagMap.equals isn't Map content equality
      assertEquals(new TreeMap<>(oneByOne.getTags()), new TreeMap<>(batched.getTags()));
      assertNull(batched.getTag("custom.removed"));
    } finally {
      oneByOne.finish();
      batched.finish();
    }
  }

  @Test
  void anExtractorSeesTheSpanTagSemanticsIncludingInterception() {
    DDSpan span = start();
    try {
      TagExtractor<String> extractor =
          (resource, target) -> {
            target.setTag(DDTags.RESOURCE_NAME, resource); // intercepted: becomes the resource
            target.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT); // intercepted: the cached kind
            target.setTag(KnownTags.HTTP_ROUTE_ID, "/orders");
            // reads inside the batch see the batch's own writes
            target.setTag("custom.route.seen", String.valueOf(target.getTag(Tags.HTTP_ROUTE)));
          };
      span.setTagsFrom("GET /orders", extractor);

      assertEquals("GET /orders", span.getResourceName().toString());
      assertEquals(Tags.SPAN_KIND_CLIENT, span.getSpanKindString());
      assertEquals("/orders", span.getTag("custom.route.seen"));
    } finally {
      span.finish();
    }
  }

  @Test
  void theSpanLevelSettersWorkInsideTheBatch() {
    DDSpan span = start();
    try {
      span.setTagsFrom(
          (TagContributor)
              target -> target.setResourceName("batched").setError(true).setMeasured(true));

      assertEquals("batched", span.getResourceName().toString());
      assertTrue(span.isError());
      assertTrue(span.isMeasured());
    } finally {
      span.finish();
    }
  }

  @Test
  void aNullSourceOrContributorIsSkipped() {
    DDSpan span = start();
    try {
      TagExtractor<String> extractor = (source, target) -> target.setTag("custom.ran", true);
      assertSame(span, span.setTagsFrom(null, extractor));
      assertSame(span, span.setTagsFrom((TagContributor) null));
      assertFalse(span.getTags().containsKey("custom.ran"));
    } finally {
      span.finish();
    }
  }

  @Test
  void theBatchsSpanDataMustNotOutliveTheCall() {
    DDSpan span = start();
    try {
      AtomicReference<SpanData> kept = new AtomicReference<>();
      span.setTagsFrom((TagContributor) kept::set);

      // assertions are on in tests, so a write outside the lock is caught
      assertThrows(AssertionError.class, () -> kept.get().setTag("custom.late", "late"));
    } finally {
      span.finish();
    }
  }
}
