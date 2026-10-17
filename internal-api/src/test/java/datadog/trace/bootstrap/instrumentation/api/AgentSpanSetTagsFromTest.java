package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link AgentSpan#setTagsFrom}: applying a {@link TagExtractor} or a {@link TagContributor}. */
class AgentSpanSetTagsFromTest {
  private final AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
  private final List<Object> calls = new ArrayList<>();

  @Test
  void appliesTheExtractorToTheSourceWithAWriterForThisSpan() {
    TagExtractor<String> extractor =
        (source, writer) -> {
          calls.add(source);
          writer.setTag("tag", (Object) "value");
        };

    assertSame(span, span.setTagsFrom("source", extractor));

    assertEquals(1, calls.size());
    assertEquals("source", calls.get(0));
    verify(span).setTag("tag", (Object) "value");
  }

  @Test
  void skipsTheExtractorForANullSource() {
    TagExtractor<String> extractor = (source, target) -> calls.add(source);

    assertSame(span, span.setTagsFrom(null, extractor));

    assertTrue(calls.isEmpty());
  }

  @Test
  void appliesTheContributorWithAWriterForThisSpan() {
    TagContributor contributor = writer -> writer.setTag(42L, 7);

    assertSame(span, span.setTagsFrom(contributor));

    verify(span).setTag(42L, 7);
  }

  @Test
  void skipsANullContributor() {
    assertSame(span, span.setTagsFrom((TagContributor) null));
  }

  @Test
  void skipsTheExtractorOnANoopSpan() {
    TagExtractor<String> extractor = (source, target) -> calls.add(source);

    assertSame(NoopSpan.INSTANCE, NoopSpan.INSTANCE.setTagsFrom("source", extractor));

    assertTrue(calls.isEmpty());
  }

  @Test
  void skipsTheContributorOnANoopSpan() {
    TagContributor contributor = calls::add;

    assertSame(NoopSpan.INSTANCE, NoopSpan.INSTANCE.setTagsFrom(contributor));

    assertTrue(calls.isEmpty());
  }
}
