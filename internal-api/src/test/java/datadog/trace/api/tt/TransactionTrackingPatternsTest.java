package datadog.trace.api.tt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
  void matchesAny_literalSetFastPath() {
    List<String> patterns = new ArrayList<>();
    patterns.add("X-Request-Id");
    patterns.add("X-Tenant-Id");
    patterns.add("X-Customer-Id");
    patterns.add("X-Correlation-Id");
    patterns.add("X-Session-Id");
    patterns.add("X-Trace-Id");
    patterns.add("X-Span-Id");
    patterns.add("X-Account-Id");
    patterns.add("X-Order-Id");
    patterns.add("X-User-Id");
    patterns.add("x-debug-*");
    TransactionTrackingPatterns.update(patterns);

    assertEquals(10, TransactionTrackingPatterns.literalCountForTest());
    assertEquals(1, TransactionTrackingPatterns.wildcardCountForTest());

    // literal matches (mixed case in candidates)
    assertTrue(TransactionTrackingPatterns.matchesAny("x-request-id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-TENANT-ID"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x-Customer-Id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-correlation-id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-Session-Id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x-TRACE-id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-Span-Id"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x-account-ID"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-ORDER-ID"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-User-Id"));
    // wildcard match
    assertTrue(TransactionTrackingPatterns.matchesAny("X-Debug-Flag"));
    // unrelated name
    assertFalse(TransactionTrackingPatterns.matchesAny("Content-Type"));
  }

  @Test
  void matchesAny_allLiteralsNoWildcardListWalk() {
    TransactionTrackingPatterns.update(
        Arrays.asList("a-one", "b-two", "c-three", "d-four", "e-five"));
    assertEquals(5, TransactionTrackingPatterns.literalCountForTest());
    assertEquals(0, TransactionTrackingPatterns.wildcardCountForTest());
    assertTrue(TransactionTrackingPatterns.matchesAny("A-ONE"));
    assertFalse(TransactionTrackingPatterns.matchesAny("missing"));
  }

  @Test
  void matchesAny_nullAndEmptyInputsSafe() {
    // No patterns configured: null and empty candidates must not throw.
    assertFalse(TransactionTrackingPatterns.matchesAny(null));
    assertFalse(TransactionTrackingPatterns.matchesAny(""));
    // With patterns configured (literal + wildcard): same expectations.
    TransactionTrackingPatterns.update(Arrays.asList("x-keep", "x-*"));
    assertFalse(TransactionTrackingPatterns.matchesAny(null));
    // "x-*" matches empty-prefix; "" doesn't start with "x-" so should not match.
    assertFalse(TransactionTrackingPatterns.matchesAny(""));
  }

  @Test
  void matchesAny_nonAsciiName() {
    TransactionTrackingPatterns.update(Collections.singletonList("X-\u0422\u0435\u0441\u0442"));
    assertTrue(TransactionTrackingPatterns.matchesAny("x-\u0422\u0435\u0441\u0442"));
    assertFalse(TransactionTrackingPatterns.matchesAny("x-other"));
  }

  @Test
  void matchesAny_caseInsensitiveSetCollisions() {
    // "x-a" and "x-i" both have (String.hashCode() & 7) == 4, so on a cap=8 table
    // (the size used for 2 literals) they collide and exercise linear probing.
    TransactionTrackingPatterns.update(Arrays.asList("x-a", "x-i"));
    assertEquals(2, TransactionTrackingPatterns.literalCountForTest());
    assertTrue(TransactionTrackingPatterns.matchesAny("X-A"));
    assertTrue(TransactionTrackingPatterns.matchesAny("X-I"));
    assertFalse(TransactionTrackingPatterns.matchesAny("X-B"));
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
