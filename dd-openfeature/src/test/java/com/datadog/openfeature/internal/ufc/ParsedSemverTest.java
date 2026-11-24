package com.datadog.openfeature.internal.ufc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
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
        Arguments.of("1.2.3-alpha-1+build.001", 1L, 2L, 3L, "alpha-1"),
        Arguments.of(
            "1.49.3-beta.11.rc-7+build.20261117.sha.0123456789abcdef",
            1L,
            49L,
            3L,
            "beta.11.rc-7"));
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
        "v1.2.3",
        "01.2.3",
        "1.02.3",
        "1.2.03",
        "18446744073709551616.0.0",
        "0.18446744073709551616.0",
        "0.0.18446744073709551616",
        "99999999999999999999.0.0",
        "1.2.3-+build",
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
  void normalizesMissingCorePartsAndComparesExtendedCoreParts() {
    assertEquals(0, ParsedSemver.compare(ParsedSemver.parse("18"), ParsedSemver.parse("18.0.0")));
    assertEquals(0, ParsedSemver.compare(ParsedSemver.parse("18.0"), ParsedSemver.parse("18.0.0")));
    assertEquals(
        0, ParsedSemver.compare(ParsedSemver.parse("1.2.3"), ParsedSemver.parse("1.2.3.0")));
    assertEquals(
        0, ParsedSemver.compare(ParsedSemver.parse("1.2.3"), ParsedSemver.parse("1.2.3.0.0")));
    assertTrue(
        ParsedSemver.compare(ParsedSemver.parse("1.2.3.4"), ParsedSemver.parse("1.2.3.5")) < 0);
    assertTrue(ParsedSemver.parse("1.2.3.4.5.6") != null);
    assertTrue(ParsedSemver.parse("1.2.3.4.5.6.7") != null);
    assertTrue(
        ParsedSemver.compare(ParsedSemver.parse("1.2.3.4.5"), ParsedSemver.parse("1.2.3.4.6")) < 0);
    assertTrue(
        ParsedSemver.compare(ParsedSemver.parse("1.2.3.4.5.6"), ParsedSemver.parse("1.2.3.4.5.7"))
            < 0);
    assertTrue(
        ParsedSemver.compare(
                ParsedSemver.parse("1.2.3.4.5.6.7"), ParsedSemver.parse("1.2.3.4.5.6.8"))
            < 0);
    assertTrue(
        ParsedSemver.compare(ParsedSemver.parse("18.0.0.0"), ParsedSemver.parse("17.0.0")) > 0);
    assertTrue(
        ParsedSemver.compare(ParsedSemver.parse("18.0.0.0.0"), ParsedSemver.parse("17.0.0")) > 0);
  }

  @Test
  void comparesPrereleaseIdentifiersByPrecedence() {
    final String[][] ordered = {
      {"1.0.0-abc", "1.0.0-abcd"}, // a prefix precedes the longer identifier
      {"1.0.0-ab", "1.0.0-b"},
      {"1.0.0-9", "1.0.0-10"}, // numeric identifiers compare by value
      {"1.0.0-99", "1.0.0-a"}, // numeric precedes alphanumeric
      {"1.0.0-a.b", "1.0.0-a-b"}, // the identifier "a" is a prefix of "a-b"
      {"1.0.0-rc.1", "1.0.0-rc.1.0"}, // fewer identifiers precede more
      {"1.0.0-18446744073709551616", "1.0.0-18446744073709551617"},
      {"1.0.0-9.x", "1.0.0-10.x"}, // a numeric identifier that is not last compares by value
      {"1.0.0-2.beta", "1.0.0-10.beta"},
    };
    for (final String[] pair : ordered) {
      final ParsedSemver left = ParsedSemver.parse(pair[0]);
      final ParsedSemver right = ParsedSemver.parse(pair[1]);
      assertTrue(ParsedSemver.compare(left, right) < 0, pair[0] + " should precede " + pair[1]);
      assertTrue(ParsedSemver.compare(right, left) > 0, pair[1] + " should follow " + pair[0]);
    }
  }

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
  void testEqualsAndHashCodeNormalizeTrailingZeroComponents() {
    // Versions that compare as equal must also be equals() and share a hashCode, even when they
    // differ only by trailing zero components (1.2.3 vs 1.2.3.0 vs 1.2.3.0.0).
    final ParsedSemver base = ParsedSemver.parse("1.2.3");
    final ParsedSemver trailingZero = ParsedSemver.parse("1.2.3.0");
    final ParsedSemver trailingZeros = ParsedSemver.parse("1.2.3.0.0");
    assertTrue(base != null);
    assertTrue(trailingZero != null);
    assertTrue(trailingZeros != null);
    assertEquals(0, ParsedSemver.compare(base, trailingZero));
    assertEquals(0, ParsedSemver.compare(base, trailingZeros));
    assertEquals(base, trailingZero);
    assertEquals(base, trailingZeros);
    assertEquals(trailingZero, trailingZeros);
    assertEquals(base.hashCode(), trailingZero.hashCode());
    assertEquals(base.hashCode(), trailingZeros.hashCode());
    assertEquals(trailingZero.hashCode(), trailingZeros.hashCode());

    // All-zero versions of varying length are also compare-equal and must hash identically.
    final ParsedSemver zero = ParsedSemver.parse("0");
    final ParsedSemver zeroZero = ParsedSemver.parse("0.0.0.0");
    assertTrue(zero != null);
    assertTrue(zeroZero != null);
    assertEquals(0, ParsedSemver.compare(zero, zeroZero));
    assertEquals(zero, zeroZero);
    assertEquals(zero.hashCode(), zeroZero.hashCode());

    // Distinct versions must remain unequal and may differ in hash.
    final ParsedSemver release = ParsedSemver.parse("1.2.4");
    assertTrue(release != null);
    assertNotEquals(base, release);
  }

  @Test
  void testCompareSemverBuildMetadataIsIgnored() {
    final ParsedSemver left = ParsedSemver.parse("1.0.0+build.1");
    assertTrue(left != null);
    final ParsedSemver right = ParsedSemver.parse("1.0.0+build.2");
    assertTrue(right != null);
    assertEquals(0, ParsedSemver.compare(left, right));
  }

  // --- In-place comparison of a version string with a parsed comparand ---

  /** Versions beyond the lists above: omitted and extra components, edges of each part. */
  private static final String[] EDGE_VERSIONS = {
    "1",
    "1.2",
    "1.2.3.4",
    "1.2.3.0",
    "1.2.3.0.0-rc.1",
    "0",
    "0.0",
    "1.0.0-0",
    "1.0.0-0a",
    "1.0.0-a.0",
    "1.0.0--",
    "1.0.0-a-b.c-d",
    "1.0.0-rc.1+0",
    "1.0.0-rc.1+00.-.a-b",
    "1.0.0+-",
    "1.0.0-18446744073709551615",
    "18446744073709551615",
    "18446744073709551615.0.18446744073709551614",
    "18446744073709551620.0.0",
    "1.2.3.",
    "1..2",
    ".1.2",
    "1.2.3-rc.",
    "1.2.3-.rc",
    "1.2.3+.b",
    "1.2.3-rc+",
    "1.2.3+a+b",
    "1.0.0-rc.1-",
    "1.0.0x",
    "1.0.0-rc.01",
    "1.0.0-rc.00",
  };

  private static List<String> allVersions() {
    final List<String> versions = new ArrayList<>();
    validVersions().forEach(arguments -> versions.add((String) arguments.get()[0]));
    versions.addAll(invalidVersions().collect(Collectors.toList()));
    versions.addAll(Arrays.asList(ORDERED_VERSIONS));
    versions.addAll(Arrays.asList(EDGE_VERSIONS));
    return versions;
  }

  @Test
  void comparesStringsInPlaceExactlyAsParsedVersions() {
    final List<String> versions = allVersions();
    for (final String comparandVersion : versions) {
      final ParsedSemver comparand = ParsedSemver.parse(comparandVersion);
      if (comparand == null) {
        continue;
      }
      for (final String version : versions) {
        assertInPlaceComparisonMatchesParse(version, comparand);
      }
    }
  }

  @Test
  void comparesRandomStringsInPlaceExactlyAsParsedVersions() {
    final Random random = new Random(13488);
    final List<ParsedSemver> comparands = new ArrayList<>();
    for (final String version : allVersions()) {
      final ParsedSemver parsed = ParsedSemver.parse(version);
      if (parsed != null) {
        comparands.add(parsed);
      }
    }
    for (int i = 0; i < 20_000; i++) {
      final String version = randomVersion(random);
      final ParsedSemver parsed = ParsedSemver.parse(version);
      if (parsed != null) {
        comparands.add(parsed);
      }
      for (int j = 0; j < 8; j++) {
        assertInPlaceComparisonMatchesParse(
            version, comparands.get(random.nextInt(comparands.size())));
      }
    }
  }

  private static void assertInPlaceComparisonMatchesParse(
      final String version, final ParsedSemver comparand) {
    final ParsedSemver parsed = ParsedSemver.parse(version);
    final int expected =
        parsed == null ? ParsedSemver.INVALID_ORDERING : ParsedSemver.compare(parsed, comparand);
    assertEquals(
        expected, ParsedSemver.compare(version, comparand), version + " against " + comparand);
  }

  /**
   * A random string that is often a valid version: mostly digits and separators, with mutations
   * that produce invalid input.
   */
  private static String randomVersion(final Random random) {
    final StringBuilder version = new StringBuilder();
    final int coreParts = 1 + random.nextInt(5);
    for (int i = 0; i < coreParts; i++) {
      if (i > 0) {
        version.append('.');
      }
      version.append(randomNumber(random));
    }
    if (random.nextInt(3) == 0) {
      version.append('-').append(randomIdentifiers(random));
    }
    if (random.nextInt(4) == 0) {
      version.append('+').append(randomIdentifiers(random));
    }
    if (random.nextInt(4) == 0) {
      final String alphabet = "0123456789.-+aZx_ ";
      final int at = random.nextInt(version.length() + 1);
      final char c = alphabet.charAt(random.nextInt(alphabet.length()));
      if (random.nextBoolean() && at < version.length()) {
        version.setCharAt(at, c);
      } else {
        version.insert(at, c);
      }
    }
    return version.toString();
  }

  private static String randomNumber(final Random random) {
    switch (random.nextInt(6)) {
      case 0:
        return "0";
      case 1:
        return "18446744073709551615";
      case 2:
        return "1844674407370955161" + random.nextInt(10);
      case 3:
        return Long.toString(random.nextInt(10), 10) + random.nextInt(100);
      default:
        return Integer.toString(random.nextInt(12));
    }
  }

  private static String randomIdentifiers(final Random random) {
    final String[] identifiers = {"alpha", "beta", "rc", "0", "1", "2", "11", "01", "a-b", "-", ""};
    final StringBuilder value = new StringBuilder();
    final int count = 1 + random.nextInt(4);
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        value.append('.');
      }
      value.append(identifiers[random.nextInt(identifiers.length)]);
    }
    return value.toString();
  }

  @Test
  void rejectsNonAsciiDigitsWhenParsingAndInPlace() {
    final ParsedSemver comparand = ParsedSemver.parse("1.2.3");
    final String[] versions = {
      "\u0661.2.3",
      "1.\u0662.3",
      "1.2.\u0663",
      "\uff11.0.0",
      "1.2.3-\u0661",
      "1.2.3-rc.\u0661",
      "1.2.3+\u0661"
    };
    for (final String version : versions) {
      assertNull(ParsedSemver.parse(version), version);
      assertEquals(
          ParsedSemver.INVALID_ORDERING, ParsedSemver.compare(version, comparand), version);
    }
  }

  @Test
  void comparesRandomSuffixesOfEqualCoresInPlaceExactlyAsParsedVersions() {
    final Random random = new Random(13489);
    final String[] cores = {"1.0.0", "1.0", "1", "1.0.0.0"};
    for (int i = 0; i < 20_000; i++) {
      final ParsedSemver comparand =
          ParsedSemver.parse(cores[random.nextInt(cores.length)] + randomSuffix(random));
      if (comparand == null) {
        continue;
      }
      assertInPlaceComparisonMatchesParse(
          cores[random.nextInt(cores.length)] + randomSuffix(random), comparand);
    }
  }

  private static String randomSuffix(final Random random) {
    final StringBuilder suffix = new StringBuilder();
    if (random.nextInt(4) != 0) {
      suffix.append('-').append(randomIdentifiers(random));
    }
    if (random.nextInt(3) == 0) {
      suffix.append('+').append(randomIdentifiers(random));
    }
    return suffix.toString();
  }

  @Test
  void comparesStringsInPlaceByPrecedence() {
    final String[][] ordered = {
      {"1.0.0-abc", "1.0.0-abcd"},
      {"1.0.0-a.b", "1.0.0-a-b"},
      {"1.0.0-9.x", "1.0.0-10.x"},
      {"1.0.0-rc.1", "1.0.0-rc.1.0"},
      {"1.0.0-99", "1.0.0-a"},
      {"1.0.0-18446744073709551616", "1.0.0-18446744073709551617"},
      {"1.0.0-a+b.c", "1.0.0-a.b"},
      {"1.0.0-9+x.1", "1.0.0-10"},
      {"1.2.3", "1.2.3.1"},
      {"18446744073709551615", "18446744073709551615.0.18446744073709551615"},
    };
    for (final String[] pair : ordered) {
      assertTrue(ParsedSemver.compare(pair[0], ParsedSemver.parse(pair[1])) < 0, pair[0]);
      assertTrue(ParsedSemver.compare(pair[1], ParsedSemver.parse(pair[0])) > 0, pair[1]);
    }
    for (int i = 0; i < ORDERED_VERSIONS.length; i++) {
      for (int j = 0; j < ORDERED_VERSIONS.length; j++) {
        final int ordering =
            ParsedSemver.compare(
                ORDERED_VERSIONS[i] + "+build.1", ParsedSemver.parse(ORDERED_VERSIONS[j]));
        assertEquals(
            Integer.signum(Integer.compare(i, j)),
            Integer.signum(ordering),
            ORDERED_VERSIONS[i] + " vs " + ORDERED_VERSIONS[j]);
      }
    }
  }
}
