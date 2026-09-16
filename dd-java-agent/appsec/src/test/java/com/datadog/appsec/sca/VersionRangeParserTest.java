package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionRangeParserTest {

  // --- matchesAny: null / empty guards ---

  @Test
  void nullVersionReturnsFalse() {
    assertFalse(VersionRangeParser.matchesAny(null, Arrays.asList("< 2.0.0")));
  }

  @Test
  void emptyVersionReturnsFalse() {
    assertFalse(VersionRangeParser.matchesAny("", Arrays.asList("< 2.0.0")));
  }

  @Test
  void nullRangesReturnsFalse() {
    assertFalse(VersionRangeParser.matchesAny("1.0.0", null));
  }

  @Test
  void emptyRangesReturnsFalse() {
    assertFalse(VersionRangeParser.matchesAny("1.0.0", Collections.emptyList()));
  }

  // --- single-condition operators ---

  @Test
  void lessThan_belowBound() {
    assertTrue(VersionRangeParser.matchesAny("2.6.7.2", Arrays.asList("< 2.6.7.3")));
  }

  @Test
  void lessThan_atBound() {
    assertFalse(VersionRangeParser.matchesAny("2.6.7.3", Arrays.asList("< 2.6.7.3")));
  }

  @Test
  void lessThan_aboveBound() {
    assertFalse(VersionRangeParser.matchesAny("2.6.7.4", Arrays.asList("< 2.6.7.3")));
  }

  @Test
  void lessThanOrEqual_atBound() {
    assertTrue(VersionRangeParser.matchesAny("2.6.7.3", Arrays.asList("<= 2.6.7.3")));
  }

  @Test
  void lessThanOrEqual_aboveBound() {
    assertFalse(VersionRangeParser.matchesAny("2.6.7.4", Arrays.asList("<= 2.6.7.3")));
  }

  @Test
  void greaterThan_aboveBound() {
    assertTrue(VersionRangeParser.matchesAny("9.5.1", Arrays.asList("> 9.5.0")));
  }

  @Test
  void greaterThan_atBound() {
    assertFalse(VersionRangeParser.matchesAny("9.5.0", Arrays.asList("> 9.5.0")));
  }

  @Test
  void greaterThanOrEqual_atBound() {
    assertTrue(VersionRangeParser.matchesAny("9.5.0", Arrays.asList(">= 9.5.0")));
  }

  @Test
  void exactMatch_matches() {
    assertTrue(VersionRangeParser.matchesAny("9.5.0", Arrays.asList("= 9.5.0")));
  }

  @Test
  void exactMatch_doesNotMatch() {
    assertFalse(VersionRangeParser.matchesAny("9.5.1", Arrays.asList("= 9.5.0")));
  }

  // --- compound condition (AND within one string) ---

  @Test
  void compoundRange_withinBounds() {
    assertTrue(VersionRangeParser.matchesAny("2.7.5", Arrays.asList(">= 2.7.0, < 2.7.9.5")));
  }

  @Test
  void compoundRange_realGhsaJackson() {
    List<String> ranges = Arrays.asList("< 2.6.7.3", ">= 2.7.0, < 2.7.9.5", ">= 2.8.0, < 2.8.11.3");
    assertTrue(VersionRangeParser.matchesAny("2.8.5", ranges));
    assertTrue(VersionRangeParser.matchesAny("2.7.5", ranges));
    assertTrue(VersionRangeParser.matchesAny("2.6.0", ranges));
    assertFalse(VersionRangeParser.matchesAny("2.9.7", ranges));
  }

  @Test
  void compoundRange_atLowerBound() {
    assertTrue(VersionRangeParser.matchesAny("2.7.0", Arrays.asList(">= 2.7.0, < 2.7.9.5")));
  }

  @Test
  void compoundRange_atUpperBound() {
    assertFalse(VersionRangeParser.matchesAny("2.7.9.5", Arrays.asList(">= 2.7.0, < 2.7.9.5")));
  }

  @Test
  void compoundRange_belowLowerBound() {
    assertFalse(VersionRangeParser.matchesAny("2.6.9", Arrays.asList(">= 2.7.0, < 2.7.9.5")));
  }

  // --- OR across multiple range strings ---

  @Test
  void multipleRanges_matchesFirstRange() {
    List<String> ranges = Arrays.asList("< 2.6.7.3", ">= 2.7.0, < 2.7.9.5");
    assertTrue(VersionRangeParser.matchesAny("2.6.0", ranges));
  }

  @Test
  void multipleRanges_matchesSecondRange() {
    List<String> ranges = Arrays.asList("< 2.6.7.3", ">= 2.7.0, < 2.7.9.5");
    assertTrue(VersionRangeParser.matchesAny("2.7.5", ranges));
  }

  @Test
  void multipleRanges_matchesNeitherRange() {
    List<String> ranges = Arrays.asList("< 2.6.7.3", ">= 2.7.0, < 2.7.9.5");
    assertFalse(VersionRangeParser.matchesAny("2.6.8", ranges));
  }

  // --- Maven qualifier handling (Gap 10) ---

  @Test
  void releaseQualifier_belowBound() {
    assertTrue(VersionRangeParser.matchesAny("5.2.19.RELEASE", Arrays.asList("< 5.2.20.RELEASE")));
  }

  @Test
  void releaseQualifier_atBound() {
    assertFalse(VersionRangeParser.matchesAny("5.2.20.RELEASE", Arrays.asList("< 5.2.20.RELEASE")));
  }

  @Test
  void releaseQualifier_equivalentToPlain() {
    // 5.2.20.RELEASE == 5.2.20 in Maven versioning
    assertFalse(VersionRangeParser.matchesAny("5.2.20", Arrays.asList("< 5.2.20.RELEASE")));
  }

  @Test
  void releaseQualifier_compoundRange() {
    assertTrue(VersionRangeParser.matchesAny("5.3.10", Arrays.asList(">= 5.3.0, < 5.3.18")));
    assertFalse(
        VersionRangeParser.matchesAny("5.2.20.RELEASE", Arrays.asList(">= 5.3.0, < 5.3.18")));
  }

  // --- 4-part versions ---

  @Test
  void fourPartVersion() {
    assertTrue(VersionRangeParser.matchesAny("2.6.7.2", Arrays.asList("< 2.6.7.3")));
    assertFalse(VersionRangeParser.matchesAny("2.6.7.3", Arrays.asList("< 2.6.7.3")));
    assertFalse(VersionRangeParser.matchesAny("2.6.7.4", Arrays.asList("< 2.6.7.3")));
  }

  // --- error handling ---

  @Test
  void unknownOperatorThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VersionRangeParser.matchesAny("1.0.0", Arrays.asList("~ 2.0.0")));
  }
}
