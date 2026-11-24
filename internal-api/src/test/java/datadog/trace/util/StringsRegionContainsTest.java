package datadog.trace.util;

import static datadog.trace.util.Strings.regionContains;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Boundary semantics of {@link Strings#regionContains(String, int, int, String)}. */
class StringsRegionContainsTest {

  // "abXYZcd": a0 b1 X2 Y3 Z4 c5 d6  -> "XYZ" spans [2,5).
  private static final String S = "abXYZcd";

  @Test
  void foundFullyInside() {
    assertTrue(regionContains(S, 0, S.length(), "XYZ"));
  }

  @Test
  void notPresent() {
    assertFalse(regionContains(S, 0, S.length(), "QQ"));
  }

  @Test
  void exactFit() {
    // idx == 2, idx + len == 5 == endIndex -> included.
    assertTrue(regionContains(S, 2, 5, "XYZ"));
  }

  @Test
  void straddlingEndIndexExcluded() {
    // endIndex == 4 cuts off the trailing 'Z' -> not fully inside.
    assertFalse(regionContains(S, 2, 4, "XYZ"));
  }

  @Test
  void occurrenceBeforeBeginIndexExcluded() {
    // beginIndex == 3 starts past the needle's first char -> no occurrence at/after beginIndex.
    assertFalse(regionContains(S, 3, S.length(), "XYZ"));
  }

  @Test
  void emptyRegion() {
    assertFalse(regionContains(S, 2, 2, "XYZ"));
  }

  @Test
  void matchesWholeStringContains() {
    assertTrue(regionContains("hello", 0, 5, "ll"));
    assertFalse(regionContains("hello", 0, 5, "z"));
  }

  @Test
  void occurrenceOnlyAfterEndIndexExcluded() {
    // The only "XYZ" is outside [0, 2).
    assertFalse(regionContains(S, 0, 2, "XYZ"));
  }

  @Test
  void laterOccurrenceInsideRegionFound() {
    // "ab" occurs at 0 (before the region) and at 5 (inside [3, 8)).
    assertTrue(regionContains("abxxxabyy", 3, 8, "ab"));
  }

  @Test
  void partialMatchesBeforeRealMatch() {
    // Several 'a's start a partial match before the full "aab" at 2.
    assertTrue(regionContains("aaaab", 0, 5, "aab"));
    assertFalse(regionContains("aaaab", 0, 4, "aab"));
  }

  @Test
  void needleLongerThanRegion() {
    assertFalse(regionContains(S, 2, 4, "XYZcd"));
  }

  @Test
  void beginAfterEnd() {
    assertFalse(regionContains(S, 5, 2, "c"));
  }

  @Test
  void endIndexPastStringLength() {
    assertTrue(regionContains(S, 0, 100, "cd"));
  }

  // The next two tests use a long tail after a short region, which takes the bounded-loop path
  // instead of indexOf.
  private static final String LONG_TAIL = new String(new char[100]).replace('\0', 'q') + "XYZ";

  @Test
  void shortRegionBeforeLongTail() {
    String s = "abXYZcd" + LONG_TAIL;
    assertTrue(regionContains(s, 0, 7, "XYZ"));
    assertFalse(regionContains(s, 0, 4, "XYZ"));
    // The "XYZ" at the end of the tail is outside the region.
    assertFalse(regionContains(s, 5, 7, "XYZ"));
  }

  @Test
  void shortRegionBeforeLongTailWithPartialMatches() {
    String s = "aaaab" + LONG_TAIL;
    assertTrue(regionContains(s, 0, 5, "aab"));
    assertFalse(regionContains(s, 0, 4, "aab"));
  }

  @Test
  void emptyNeedle() {
    assertTrue(regionContains(S, 2, 5, ""));
    assertTrue(regionContains(S, 2, 2, ""));
    assertFalse(regionContains(S, 5, 2, ""));
  }
}
