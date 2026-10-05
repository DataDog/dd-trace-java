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
}
