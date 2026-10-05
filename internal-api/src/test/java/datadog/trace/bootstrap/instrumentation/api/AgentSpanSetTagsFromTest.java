package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link AgentSpan#setTagsFrom}: applying a {@link TagExtractor} or a {@link TagContributor}. */
class AgentSpanSetTagsFromTest {
  private final AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
  private final List<Object> calls = new ArrayList<>();

  @Test
  void appliesTheExtractorToTheSourceAndThisSpan() {
    TagExtractor<String> extractor =
        (source, target) -> {
          calls.add(source);
          calls.add(target);
        };

    assertSame(span, span.setTagsFrom("source", extractor));

    assertEquals(2, calls.size());
    assertEquals("source", calls.get(0));
    assertSame(span, calls.get(1));
  }

  @Test
  void skipsTheExtractorForANullSource() {
    TagExtractor<String> extractor = (source, target) -> calls.add(source);

    assertSame(span, span.setTagsFrom(null, extractor));

    assertTrue(calls.isEmpty());
  }

  @Test
  void appliesTheContributorToThisSpan() {
    TagContributor contributor = calls::add;

    assertSame(span, span.setTagsFrom(contributor));

    assertEquals(1, calls.size());
    assertSame(span, calls.get(0));
  }

  @Test
  void skipsANullContributor() {
    assertSame(span, span.setTagsFrom((TagContributor) null));
  }
}
