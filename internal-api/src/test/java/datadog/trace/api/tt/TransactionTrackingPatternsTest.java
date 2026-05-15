package datadog.trace.api.tt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransactionTrackingPatternsTest {

  @AfterEach
  void reset() {
    TransactionTrackingPatterns.resetForTest();
  }

  @Test
  void emptyByDefault() {
    assertTrue(TransactionTrackingPatterns.isEmpty());
    assertFalse(TransactionTrackingPatterns.matchesAny("x-foo"));
  }

  @Test
  void nullOrEmptyUpdateKeepsEmpty() {
    TransactionTrackingPatterns.update(null);
    assertTrue(TransactionTrackingPatterns.isEmpty());
    TransactionTrackingPatterns.update(Collections.emptyList());
    assertTrue(TransactionTrackingPatterns.isEmpty());
  }

  @Test
  void literalPatternIsExactCaseInsensitive() {
    TransactionTrackingPatterns.update(Collections.singletonList("X-Request-Id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x-request-id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-REQUEST-ID"));
    assertFalse(TransactionTrackingPatterns.matchesAny("x-request-id-2"));
    assertFalse(TransactionTrackingPatterns.matchesAny("yx-request-id"));
  }

  @Test
  void prefixWildcard() {
    TransactionTrackingPatterns.update(Collections.singletonList("*-id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-Request-Id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("-id"));
    assertFalse(TransactionTrackingPatterns.matchesAny("id"));
    assertFalse(TransactionTrackingPatterns.matchesAny("X-Request-Token"));
  }

  @Test
  void suffixWildcard() {
    TransactionTrackingPatterns.update(Collections.singletonList("x-trace-*"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-Trace-Id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x-trace-"));
    assertFalse(TransactionTrackingPatterns.matchesAny("x-trace"));
    assertFalse(TransactionTrackingPatterns.matchesAny("y-trace-id"));
  }

  @Test
  void middleWildcard() {
    TransactionTrackingPatterns.update(Collections.singletonList("x-*-id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-Request-Id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x--id"));
    assertFalse(TransactionTrackingPatterns.matchesAny("x-request"));
    assertFalse(TransactionTrackingPatterns.matchesAny("y-request-id"));
  }

  @Test
  void multipleWildcards() {
    TransactionTrackingPatterns.update(Collections.singletonList("*foo*bar*"));
    assertTrue(TransactionTrackingPatterns.matchesAny("xxfooyybarzz"));
    assertTrue(TransactionTrackingPatterns.matchesAny("foobar"));
    assertFalse(TransactionTrackingPatterns.matchesAny("barfoo"));
    assertFalse(TransactionTrackingPatterns.matchesAny("fooxx"));
  }

  @Test
  void starOnlyMatchesAnything() {
    TransactionTrackingPatterns.update(Collections.singletonList("*"));
    assertTrue(TransactionTrackingPatterns.matchesAny("anything"));
    assertTrue(TransactionTrackingPatterns.matchesAny(""));
  }

  @Test
  void multiplePatternsAnyMatch() {
    TransactionTrackingPatterns.update(Arrays.asList("x-foo-*", "*-trace"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x-foo-1"));
    assertTrue(TransactionTrackingPatterns.matchesAny("dd-trace"));
    assertFalse(TransactionTrackingPatterns.matchesAny("dd-other"));
  }

  @Test
  void blankAndNullEntriesAreSkipped() {
    TransactionTrackingPatterns.update(Arrays.asList(null, "", "  ", "x-keep"));
    assertFalse(TransactionTrackingPatterns.isEmpty());
    assertTrue(TransactionTrackingPatterns.matchesAny("x-keep"));
    assertEquals(1, TransactionTrackingPatterns.currentSnapshot().size());
  }

  @Test
  void allBlankClearsSnapshot() {
    TransactionTrackingPatterns.update(Arrays.asList(null, "", "  "));
    assertTrue(TransactionTrackingPatterns.isEmpty());
  }

  @Test
  void overlappingSegmentsDoNotMatch() {
    TransactionTrackingPatterns.update(Collections.singletonList("ab*ab"));
    assertTrue(TransactionTrackingPatterns.matchesAny("abxab"));
    assertTrue(TransactionTrackingPatterns.matchesAny("abab"));
    assertFalse(TransactionTrackingPatterns.matchesAny("aba"));
    assertFalse(TransactionTrackingPatterns.matchesAny("xab"));
  }
}
