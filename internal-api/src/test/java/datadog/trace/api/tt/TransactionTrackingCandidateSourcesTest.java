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

class TransactionTrackingCandidateSourcesTest {

  @AfterEach
  void reset() {
    TransactionTrackingCandidateSources.resetForTest();
  }

  @Test
  void emptyByDefault() {
    assertTrue(TransactionTrackingCandidateSources.isEmpty());
    assertFalse(TransactionTrackingCandidateSources.matchesAny("x-foo"));
  }

  @Test
  void nullOrEmptyUpdateKeepsEmpty() {
    TransactionTrackingCandidateSources.update(null);
    assertTrue(TransactionTrackingCandidateSources.isEmpty());
    TransactionTrackingCandidateSources.update(Collections.emptyList());
    assertTrue(TransactionTrackingCandidateSources.isEmpty());
  }

  @Test
  void literalPatternIsExactCaseInsensitive() {
    TransactionTrackingCandidateSources.update(Collections.singletonList("X-Request-Id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-request-id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-REQUEST-ID"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("x-request-id-2"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("yx-request-id"));
  }

  @Test
  void prefixWildcard() {
    TransactionTrackingCandidateSources.update(Collections.singletonList("*-id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-Request-Id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("-id"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("id"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("X-Request-Token"));
  }

  @Test
  void suffixWildcard() {
    TransactionTrackingCandidateSources.update(Collections.singletonList("x-trace-*"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-Trace-Id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-trace-"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("x-trace"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("y-trace-id"));
  }

  @Test
  void middleWildcard() {
    TransactionTrackingCandidateSources.update(Collections.singletonList("x-*-id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-Request-Id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x--id"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("x-request"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("y-request-id"));
  }

  @Test
  void multipleWildcards() {
    TransactionTrackingCandidateSources.update(Collections.singletonList("*foo*bar*"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("xxfooyybarzz"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("foobar"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("barfoo"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("fooxx"));
  }

  @Test
  void starOnlyMatchesAnything() {
    TransactionTrackingCandidateSources.update(Collections.singletonList("*"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("anything"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny(""));
  }

  @Test
  void multiplePatternsAnyMatch() {
    TransactionTrackingCandidateSources.update(Arrays.asList("x-foo-*", "*-trace"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-foo-1"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("dd-trace"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("dd-other"));
  }

  @Test
  void blankAndNullEntriesAreSkipped() {
    TransactionTrackingCandidateSources.update(Arrays.asList(null, "", "  ", "x-keep"));
    assertFalse(TransactionTrackingCandidateSources.isEmpty());
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-keep"));
    assertEquals(1, TransactionTrackingCandidateSources.currentSnapshot().size());
  }

  @Test
  void allBlankClearsSnapshot() {
    TransactionTrackingCandidateSources.update(Arrays.asList(null, "", "  "));
    assertTrue(TransactionTrackingCandidateSources.isEmpty());
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
    TransactionTrackingCandidateSources.update(patterns);

    assertEquals(10, TransactionTrackingCandidateSources.literalCountForTest());
    assertEquals(1, TransactionTrackingCandidateSources.wildcardCountForTest());

    // literal matches (mixed case in candidates)
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-request-id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-TENANT-ID"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-Customer-Id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-correlation-id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-Session-Id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-TRACE-id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-Span-Id"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-account-ID"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-ORDER-ID"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-User-Id"));
    // wildcard match
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-Debug-Flag"));
    // unrelated name
    assertFalse(TransactionTrackingCandidateSources.matchesAny("Content-Type"));
  }

  @Test
  void matchesAny_allLiteralsNoWildcardListWalk() {
    TransactionTrackingCandidateSources.update(
        Arrays.asList("a-one", "b-two", "c-three", "d-four", "e-five"));
    assertEquals(5, TransactionTrackingCandidateSources.literalCountForTest());
    assertEquals(0, TransactionTrackingCandidateSources.wildcardCountForTest());
    assertTrue(TransactionTrackingCandidateSources.matchesAny("A-ONE"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("missing"));
  }

  @Test
  void matchesAny_nullAndEmptyInputsSafe() {
    // No patterns configured: null and empty candidates must not throw.
    assertFalse(TransactionTrackingCandidateSources.matchesAny(null));
    assertFalse(TransactionTrackingCandidateSources.matchesAny(""));
    // With patterns configured (literal + wildcard): same expectations.
    TransactionTrackingCandidateSources.update(Arrays.asList("x-keep", "x-*"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny(null));
    // "x-*" matches empty-prefix; "" doesn't start with "x-" so should not match.
    assertFalse(TransactionTrackingCandidateSources.matchesAny(""));
  }

  @Test
  void matchesAny_nonAsciiName() {
    TransactionTrackingCandidateSources.update(
        Collections.singletonList("X-\u0422\u0435\u0441\u0442"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("x-\u0422\u0435\u0441\u0442"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("x-other"));
  }

  @Test
  void matchesAny_caseInsensitiveSetCollisions() {
    // "x-a" and "x-i" both have (String.hashCode() & 7) == 4, so on a cap=8 table
    // (the size used for 2 literals) they collide and exercise linear probing.
    TransactionTrackingCandidateSources.update(Arrays.asList("x-a", "x-i"));
    assertEquals(2, TransactionTrackingCandidateSources.literalCountForTest());
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-A"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("X-I"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("X-B"));
  }

  @Test
  void overlappingSegmentsDoNotMatch() {
    TransactionTrackingCandidateSources.update(Collections.singletonList("ab*ab"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("abxab"));
    assertTrue(TransactionTrackingCandidateSources.matchesAny("abab"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("aba"));
    assertFalse(TransactionTrackingCandidateSources.matchesAny("xab"));
  }
}
