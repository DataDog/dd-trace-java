package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.KnownTags;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
  void aNullOrEmptyValueMakesTheTagsReplayOnly() {
    // a null removes the tag and an empty value is stored, which copying the net result in
    // wouldn't reproduce
    FrozenTags removing =
        FrozenTags.of(
            (TagContributor)
                span -> {
                  span.setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) null);
                  span.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                });
    assertTrue(removing.isReplayOnly());
    assertEquals(1, removing.tags().size());
    assertNull(removing.tags().getObject(Tags.PEER_HOSTNAME));

    assertTrue(FrozenTags.of((TagContributor) span -> span.setTag("custom", "")).isReplayOnly());
    assertFalse(FrozenTags.of((TagContributor) span -> span.setTag("custom", "x")).isReplayOnly());
  }

  @Test
  void aRemovalAloneIsNotEmpty() {
    FrozenTags frozen =
        FrozenTags.of((TagContributor) span -> span.setTag(Tags.PEER_HOSTNAME, (Object) null));

    assertFalse(frozen.isEmpty());
    assertTrue(frozen.tags().isEmpty());
  }

  @Test
  void replayMakesTheWritesInOrderWithTheirOwnSetters() {
    FrozenTags frozen =
        FrozenTags.of(
            (TagContributor)
                span -> {
                  span.setTag("custom.b", 2);
                  span.setTag(KnownTags.PEER_IPV4_ID, "10.0.0.1");
                  span.setTag("custom.a", (Object) null);
                  span.setTag("custom.b", 3L);
                });
    SpanData span = mock(SpanData.class);

    frozen.replay(span);

    InOrder order = inOrder(span);
    order.verify(span).setTag("custom.b", 2);
    order.verify(span).setTag(KnownTags.PEER_IPV4_ID, (CharSequence) "10.0.0.1");
    order.verify(span).setTag("custom.a", (Object) null);
    order.verify(span).setTag("custom.b", 3L);
    verifyNoMoreInteractions(span);
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

    verify(span).setTag(KnownTags.PEER_IPV4_ID, (CharSequence) "10.0.0.1");
  }
}
