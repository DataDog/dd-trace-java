package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagContributor;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.TagsWriter;
import datadog.trace.common.writer.ListWriter;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code DDSpan.setTagsFrom}: writes through the batched {@link TagsWriter} behave exactly like the
 * same {@code DDSpan.setTag} calls, interception and {@code DDSpan}'s own quirks included.
 */
class DDSpanSetTagsFromTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;
  private DDSpan batched;
  private DDSpan direct;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
    batched = (DDSpan) tracer.buildSpan("datadog", "batched").start();
    direct = (DDSpan) tracer.buildSpan("datadog", "direct").start();
  }

  @AfterEach
  void tearDown() {
    batched.finish();
    direct.finish();
    tracer.close();
  }

  @Test
  void aContributorWritesAsSetTagDoes() {
    applyBoth(
        w -> {
          w.setTag(KnownTags.COMPONENT_ID, "jdbc");
          w.setTag(KnownTags.HTTP_RESEND_COUNT_ID, 2);
          w.setTag(KnownTags.DD_PROFILING_ENABLED_ID, true);
          w.setTag(KnownTags.PEER_PORT_ID, 5432L);
          w.setTag("custom.string", "value");
          w.setTag("custom.int", 7);
          w.setTag("custom.double", 1.5);
          w.setTag("custom.object", (Object) "object");
        });

    assertEquals(new HashMap<>(direct.getTags()), new HashMap<>(batched.getTags()));
    assertEquals("jdbc", batched.getTag(KnownTags.COMPONENT_NAME));
    assertEquals("value", batched.getTag("custom.string"));
  }

  @Test
  void interceptedTagsStillSetSpanLevelState() {
    applyBoth(
        w -> {
          w.setTag(KnownTags.RESOURCE_NAME_ID, "SELECT ?");
          w.setTag(KnownTags.SPAN_KIND_ID, "client");
          w.setTag(KnownTags.ERROR_ID, true);
          w.setTag(KnownTags.SPAN_TYPE_ID, "sql");
        });

    assertEquals("SELECT ?", batched.getResourceName().toString());
    assertEquals("client", batched.getSpanKindString());
    assertEquals(true, batched.isError());
    assertEquals("sql", batched.getSpanType().toString());
    assertEquals(new HashMap<>(direct.getTags()), new HashMap<>(batched.getTags()));
    assertEquals(direct.getResourceName().toString(), batched.getResourceName().toString());
  }

  @Test
  void anIntStatusCodeByNameSetsTheStatusCode() {
    applyBoth(w -> w.setTag("http.status_code", 404));

    assertEquals(404, batched.getHttpStatusCode());
    assertEquals(direct.getHttpStatusCode(), batched.getHttpStatusCode());
  }

  @Test
  void anEmptyOrNullValueRemovesTheTag() {
    batched.setTag(KnownTags.PEER_HOSTNAME_ID, "db.internal");
    batched.setTag(KnownTags.COMPONENT_ID, "okhttp");
    batched.setTag("custom.tag", "value");

    batched.setTagsFrom(
        (TagContributor)
            w -> {
              w.setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) null);
              w.setTag(KnownTags.COMPONENT_ID, "");
              w.setTag("custom.tag", "");
            });

    assertNull(batched.getTag(KnownTags.PEER_HOSTNAME_NAME));
    assertNull(batched.getTag(KnownTags.COMPONENT_NAME));
    assertNull(batched.getTag("custom.tag"));
  }

  @Test
  void anExtractorReadsItsSource() {
    TagExtractor<String> extractor = (source, w) -> w.setTag(KnownTags.DB_TYPE_ID, source);

    AgentSpan result = batched.setTagsFrom("postgresql", extractor);

    assertSame(batched, result);
    assertEquals("postgresql", batched.getTag(KnownTags.DB_TYPE_NAME));
  }

  @Test
  void aNullSourceOrContributorIsSkipped() {
    TagExtractor<String> extractor = (source, w) -> w.setTag(KnownTags.DB_TYPE_ID, "never");

    assertSame(batched, batched.setTagsFrom(null, extractor));
    assertSame(batched, batched.setTagsFrom((TagContributor) null));
    assertNull(batched.getTag(KnownTags.DB_TYPE_NAME));
  }

  @Test
  void aWriterKeptPastItsCallThrows() {
    AtomicReference<TagsWriter> kept = new AtomicReference<>();
    batched.setTagsFrom((TagContributor) kept::set);

    assertThrows(
        IllegalStateException.class, () -> kept.get().setTag(KnownTags.DB_TYPE_ID, "late"));
    assertNull(batched.getTag(KnownTags.DB_TYPE_NAME));
  }

  @Test
  void aWriterUsedFromAnotherThreadThrows() {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    batched.setTagsFrom(
        (TagContributor)
            w -> {
              Thread other =
                  new Thread(
                      () -> {
                        try {
                          w.setTag(KnownTags.DB_TYPE_ID, "racy");
                        } catch (Throwable t) {
                          failure.set(t);
                        }
                      });
              other.start();
              try {
                other.join();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    assertEquals(IllegalStateException.class, failure.get().getClass());
    assertNull(batched.getTag(KnownTags.DB_TYPE_NAME));
  }

  /** Applies {@code writes} to one span through setTagsFrom and to the other through setTag. */
  private void applyBoth(Consumer<TagsWriter> writes) {
    batched.setTagsFrom((TagContributor) writes::accept);
    writes.accept(new DirectWriter(direct));
  }

  /** Forwards each write to {@code DDSpan.setTag}, the behavior a batched write must match. */
  private static final class DirectWriter implements TagsWriter {
    private final DDSpan span;

    DirectWriter(DDSpan span) {
      this.span = span;
    }

    @Override
    public void setTag(long tagId, boolean value) {
      span.setTag(tagId, value);
    }

    @Override
    public void setTag(long tagId, int value) {
      span.setTag(tagId, value);
    }

    @Override
    public void setTag(long tagId, long value) {
      span.setTag(tagId, value);
    }

    @Override
    public void setTag(long tagId, float value) {
      span.setTag(tagId, value);
    }

    @Override
    public void setTag(long tagId, double value) {
      span.setTag(tagId, value);
    }

    @Override
    public void setTag(long tagId, CharSequence value) {
      span.setTag(tagId, value);
    }

    @Override
    public void setTag(long tagId, Object value) {
      span.setTag(tagId, value);
    }

    @Override
    public void setTag(String tag, boolean value) {
      span.setTag(tag, value);
    }

    @Override
    public void setTag(String tag, int value) {
      span.setTag(tag, value);
    }

    @Override
    public void setTag(String tag, long value) {
      span.setTag(tag, value);
    }

    @Override
    public void setTag(String tag, float value) {
      span.setTag(tag, value);
    }

    @Override
    public void setTag(String tag, double value) {
      span.setTag(tag, value);
    }

    @Override
    public void setTag(String tag, CharSequence value) {
      span.setTag(tag, value);
    }

    @Override
    public void setTag(String tag, Object value) {
      span.setTag(tag, value);
    }
  }
}
