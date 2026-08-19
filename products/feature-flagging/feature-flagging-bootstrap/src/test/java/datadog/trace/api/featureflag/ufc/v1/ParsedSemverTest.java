package datadog.trace.api.featureflag.ufc.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ParsedSemverTest {

  // --- Parse tests (ported from Go semver_test.go TestParseSemver) ---

  static Stream<Arguments> validVersions() {
    return Stream.of(
        Arguments.of("0.0.0", 0L, 0L, 0L, ""),
        Arguments.of(
            "18446744073709551615.18446744073709551615.18446744073709551615",
            Long.parseUnsignedLong("18446744073709551615"),
            Long.parseUnsignedLong("18446744073709551615"),
            Long.parseUnsignedLong("18446744073709551615"),
            ""),
        Arguments.of("1.2.3-alpha.1", 1L, 2L, 3L, "alpha.1"),
        Arguments.of("1.2.3-18446744073709551616", 1L, 2L, 3L, "18446744073709551616"),
        Arguments.of("1.2.3+build.001", 1L, 2L, 3L, ""),
        Arguments.of("1.2.3-alpha-1+build.001", 1L, 2L, 3L, "alpha-1"));
  }

  @ParameterizedTest(name = "valid: {0}")
  @MethodSource("validVersions")
  void testParseValid(
      final String version,
      final long major,
      final long minor,
      final long patch,
      final String prerelease) {
    final ParsedSemver parsed = ParsedSemver.parse(version);
    assertTrue(parsed != null, "expected " + version + " to parse");
    assertEquals(major, parsed.getMajor());
    assertEquals(minor, parsed.getMinor());
    assertEquals(patch, parsed.getPatch());
    assertEquals(prerelease, parsed.getPrerelease());
  }

  static Stream<String> invalidVersions() {
    return Stream.of(
        "",
        "x",
        "1",
        "1.2",
        "1.2.3.4",
        "v1.2.3",
        "01.2.3",
        "1.02.3",
        "1.2.03",
        "18446744073709551616.0.0",
        "0.18446744073709551616.0",
        "0.0.18446744073709551616",
        "1.2.3-",
        "1.2.3+",
        "1.2.3-alpha..1",
        "1.2.3+build..1",
        "1.2.3-01",
        "1.2.3-alpha_1",
        "1.2.3-alpha+build+other",
        "1.2.3-α",
        " 1.2.3",
        "1.2.3 ");
  }

  @ParameterizedTest(name = "invalid: {0}")
  @MethodSource("invalidVersions")
  void testParseInvalid(final String version) {
    assertNull(ParsedSemver.parse(version), "expected " + version + " to be invalid");
  }

  // --- Compare tests (ported from Go semver_test.go TestCompareSemver) ---

  /** The canonical SemVer precedence ordering from the spec. */
  private static final String[] ORDERED_VERSIONS = {
    "1.0.0-alpha",
    "1.0.0-alpha.1",
    "1.0.0-alpha.beta",
    "1.0.0-beta",
    "1.0.0-beta.2",
    "1.0.0-beta.11",
    "1.0.0-rc.1",
    "1.0.0",
    "1.0.1",
    "1.1.0",
    "2.0.0",
  };

  @Test
  void testCompareSemverOrdering() {
    for (int i = 0; i < ORDERED_VERSIONS.length; i++) {
      final ParsedSemver left = ParsedSemver.parse(ORDERED_VERSIONS[i]);
      assertTrue(left != null, "left parse failed: " + ORDERED_VERSIONS[i]);
      for (int j = 0; j < ORDERED_VERSIONS.length; j++) {
        final ParsedSemver right = ParsedSemver.parse(ORDERED_VERSIONS[j]);
        assertTrue(right != null, "right parse failed: " + ORDERED_VERSIONS[j]);
        final int ordering = ParsedSemver.compare(left, right);
        if (i < j) {
          assertTrue(ordering < 0, ORDERED_VERSIONS[i] + " should precede " + ORDERED_VERSIONS[j]);
        } else if (i > j) {
          assertTrue(ordering > 0, ORDERED_VERSIONS[i] + " should follow " + ORDERED_VERSIONS[j]);
        } else {
          assertEquals(0, ordering);
        }
      }
    }
  }

  @Test
  void testCompareSemverArbitrarilyLargeNumericPrerelease() {
    final ParsedSemver left = ParsedSemver.parse("1.0.0-99999999999999999999");
    assertTrue(left != null);
    final ParsedSemver right = ParsedSemver.parse("1.0.0-100000000000000000000");
    assertTrue(right != null);
    assertTrue(ParsedSemver.compare(left, right) < 0);
  }

  @Test
  void testValueObjectMethods() {
    final ParsedSemver value = ParsedSemver.parse("1.2.3-alpha");
    final ParsedSemver equalValue = ParsedSemver.parse("1.2.3-alpha");
    final ParsedSemver release = ParsedSemver.parse("1.2.3");
    assertTrue(value != null);
    assertTrue(equalValue != null);
    assertTrue(release != null);
    assertTrue(value.equals(value));
    assertTrue(value.equals(equalValue));
    assertFalse(value.equals(null));
    assertFalse(value.equals("1.2.3-alpha"));
    assertEquals(value.hashCode(), equalValue.hashCode());
    assertEquals("1.2.3-alpha", value.toString());
    assertEquals("1.2.3", release.toString());
  }

  @Test
  void testCompareSemverNumericPrereleaseIdentifiersLexicographically() {
    final ParsedSemver left = ParsedSemver.parse("1.0.0-10");
    assertTrue(left != null);
    final ParsedSemver right = ParsedSemver.parse("1.0.0-11");
    assertTrue(right != null);
    assertTrue(ParsedSemver.compare(left, right) < 0);
  }

  @Test
  void testCompareSemverBuildMetadataIsIgnored() {
    final ParsedSemver left = ParsedSemver.parse("1.0.0+build.1");
    assertTrue(left != null);
    final ParsedSemver right = ParsedSemver.parse("1.0.0+build.2");
    assertTrue(right != null);
    assertEquals(0, ParsedSemver.compare(left, right));
  }
}
