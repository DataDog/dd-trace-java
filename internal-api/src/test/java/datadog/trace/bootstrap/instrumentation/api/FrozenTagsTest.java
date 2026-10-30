package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import org.junit.jupiter.api.Test;

class FrozenTagsTest {

  @Test
  void recordsTheWritesFrozen() {
    TagExtractor<String> extractor =
        (ip, span) -> {
          span.setTag(KnownTags.PEER_IPV4_ID, ip);
          span.setTag(Tags.PEER_PORT, 5432);
          span.setTag("custom.tag", true);
        };
    FrozenTags frozen = FrozenTags.of("10.0.0.1", extractor);

    assertTrue(frozen.tags().isFrozen());
    assertEquals(3, frozen.tags().size());
    assertEquals("10.0.0.1", frozen.tags().getObject(Tags.PEER_HOST_IPV4));
    assertEquals(5432, frozen.tags().getObject(Tags.PEER_PORT));
    assertEquals(true, frozen.tags().getObject("custom.tag"));
  }

  @Test
  void aLaterWriteOfTheSameTagWins() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                span -> {
                  span.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                  span.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.2");
                });

    assertEquals(1, frozen.tags().size());
    assertEquals("10.0.0.2", frozen.tags().getObject(Tags.PEER_HOST_IPV4));
  }

  @Test
  void aNullValueIsNoWrite() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                span -> {
                  span.setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) null);
                  span.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                });

    assertEquals(1, frozen.tags().size());
    assertNull(frozen.tags().getObject(Tags.PEER_HOSTNAME));
  }

  @Test
  void nothingRecordedIsEmpty() {
    assertSame(FrozenTags.EMPTY, FrozenTags.of((TagContributor) span -> {}));
    assertSame(FrozenTags.EMPTY, FrozenTags.of(null, (source, span) -> span.setTag("x", 1)));
    assertSame(FrozenTags.EMPTY, FrozenTags.of((TagContributor) null));
    assertTrue(FrozenTags.EMPTY.isEmpty());
  }

  @Test
  void readingTheSpanCannotBeFrozen() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> FrozenTags.of((TagContributor) span -> span.getTag(Tags.PEER_HOSTNAME)));
  }

  @Test
  void spanLevelStateCannotBeFrozen() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> FrozenTags.of((TagContributor) span -> span.setResourceName("r")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> FrozenTags.of((TagContributor) span -> span.setError(true)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> FrozenTags.of((TagContributor) span -> span.setMeasured(true)));
  }

  @Test
  void theDefaultSetTagsFromReplaysTheTags() {
    FrozenTags frozen =
        FrozenTags.of("10.0.0.1", (ip, span) -> span.setTag(KnownTags.PEER_IPV4_ID, ip));
    AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);

    span.setTagsFrom(frozen);

    verify(span)
        .setTag(
            argThat(
                (TagMap.EntryReader entry) ->
                    entry.tagId() == KnownTags.PEER_IPV4_ID
                        && "10.0.0.1".equals(entry.objectValue())));
  }
}
