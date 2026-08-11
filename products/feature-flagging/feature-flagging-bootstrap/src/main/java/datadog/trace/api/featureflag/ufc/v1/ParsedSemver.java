package datadog.trace.api.featureflag.ufc.v1;

/**
 * ParsedSemver is the language-neutral representation of the Rust/Eppo SemVer subset used by FFE.
 * Owning this parser and comparator keeps behavior consistent across SDKs and lets configuration
 * preprocessing cache comparands instead of reparsing them during every evaluation.
 *
 * <p>Core identifiers (major, minor, patch) are limited to unsigned 64-bit integers, stored in
 * {@code long} fields and compared with unsigned semantics. Numeric prerelease identifiers may be
 * arbitrarily large and are compared by length-then-lexicographic order. Build metadata is
 * validated during parsing but not retained because it does not affect SemVer precedence.
 */
public final class ParsedSemver {

  /** Sentinel returned by {@link #parse(String)} when the input is not a valid semantic version. */
  public static final ParsedSemver INVALID = null;

  private final long major; // unsigned
  private final long minor; // unsigned
  private final long patch; // unsigned
  private final String prerelease;

  ParsedSemver(final long major, final long minor, final long patch, final String prerelease) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.prerelease = prerelease;
  }

  /**
   * Parses a semantic version string. Accepts the same version syntax as Rust's {@code
   * semver::Version::parse}.
   *
   * @param version the version string to parse
   * @return a {@link ParsedSemver} instance, or {@code null} if the input is not a valid semantic
   *     version
   */
  public static ParsedSemver parse(final String version) {
    // Parse major
    final long[] majorResult = parseCoreIdentifier(version, 0);
    if (majorResult == null) {
      return INVALID;
    }
    final long major = majorResult[0];
    int next = (int) majorResult[1];
    if (next >= version.length() || version.charAt(next) != '.') {
      return INVALID;
    }

    // Parse minor
    final long[] minorResult = parseCoreIdentifier(version, next + 1);
    if (minorResult == null) {
      return INVALID;
    }
    final long minor = minorResult[0];
    next = (int) minorResult[1];
    if (next >= version.length() || version.charAt(next) != '.') {
      return INVALID;
    }

    // Parse patch
    final long[] patchResult = parseCoreIdentifier(version, next + 1);
    if (patchResult == null) {
      return INVALID;
    }
    final long patch = patchResult[0];
    next = (int) patchResult[1];

    final ParsedSemver parsed = new ParsedSemver(major, minor, patch, "");
    if (next == version.length()) {
      return parsed;
    }

    // Parse prerelease and/or build metadata
    String remainder = version.substring(next);
    String prerelease = "";

    if (remainder.charAt(0) == '-') {
      remainder = remainder.substring(1);
      final int buildStart = remainder.indexOf('+');
      if (buildStart == -1) {
        if (!validSemverIdentifiers(remainder, false)) {
          return INVALID;
        }
        prerelease = remainder;
        return new ParsedSemver(major, minor, patch, prerelease);
      }

      prerelease = remainder.substring(0, buildStart);
      if (!validSemverIdentifiers(prerelease, false)) {
        return INVALID;
      }
      remainder = remainder.substring(buildStart + 1);
    } else if (remainder.charAt(0) == '+') {
      remainder = remainder.substring(1);
    } else {
      return INVALID;
    }

    if (!validSemverIdentifiers(remainder, true)) {
      return INVALID;
    }
    return new ParsedSemver(major, minor, patch, prerelease);
  }

  /**
   * Compares two parsed semantic versions by precedence. Build metadata is intentionally ignored.
   *
   * @param left the left operand
   * @param right the right operand
   * @return negative if {@code left} precedes {@code right}, positive if {@code left} follows
   *     {@code right}, zero if they have equal precedence
   */
  public static int compare(final ParsedSemver left, final ParsedSemver right) {
    int cmp = Long.compareUnsigned(left.major, right.major);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Long.compareUnsigned(left.minor, right.minor);
    if (cmp != 0) {
      return cmp;
    }
    cmp = Long.compareUnsigned(left.patch, right.patch);
    if (cmp != 0) {
      return cmp;
    }
    return comparePrerelease(left.prerelease, right.prerelease);
  }

  /**
   * Parses a core version identifier (major, minor, or patch). Enforces Rust's uint64 bound without
   * accepting leading zeros (except for the value zero itself).
   *
   * @return a two-element array {@code {value, nextIndex}}, or {@code null} on failure
   */
  private static long[] parseCoreIdentifier(final String version, final int start) {
    if (start >= version.length() || !isASCIIDigit(version.charAt(start))) {
      return null;
    }
    if (version.charAt(start) == '0') {
      return new long[] {0, start + 1};
    }

    int end = start;
    while (end < version.length() && isASCIIDigit(version.charAt(end))) {
      end++;
    }
    try {
      final long value = Long.parseUnsignedLong(version.substring(start, end));
      return new long[] {value, end};
    } catch (final NumberFormatException e) {
      return null; // overflow
    }
  }

  /**
   * Validates dot-separated identifiers. Permits leading zeros for build metadata only; numeric
   * prerelease identifiers reject them.
   */
  private static boolean validSemverIdentifiers(
      final String value, final boolean allowLeadingZeros) {
    int identifierStart = 0;
    boolean identifierNumeric = true;
    for (int i = 0; i <= value.length(); i++) {
      if (i == value.length() || value.charAt(i) == '.') {
        if (i == identifierStart) {
          return false; // empty identifier
        }
        if (!allowLeadingZeros
            && identifierNumeric
            && i - identifierStart > 1
            && value.charAt(identifierStart) == '0') {
          return false; // leading zero in numeric identifier
        }
        identifierStart = i + 1;
        identifierNumeric = true;
        continue;
      }

      final char c = value.charAt(i);
      if (!isASCIIAlphanumeric(c) && c != '-') {
        return false;
      }
      if (!isASCIIDigit(c)) {
        identifierNumeric = false;
      }
    }
    return true;
  }

  private static int comparePrerelease(final String left, final String right) {
    if (left.equals(right)) {
      return 0;
    }
    if (left.isEmpty()) {
      return 1; // release > prerelease
    }
    if (right.isEmpty()) {
      return -1; // prerelease < release
    }

    int leftPos = 0;
    int rightPos = 0;
    while (true) {
      // Extract next identifier from each side
      int leftDot = left.indexOf('.', leftPos);
      int rightDot = right.indexOf('.', rightPos);
      final String leftIdentifier =
          leftDot == -1 ? left.substring(leftPos) : left.substring(leftPos, leftDot);
      final String rightIdentifier =
          rightDot == -1 ? right.substring(rightPos) : right.substring(rightPos, rightDot);

      final int ordering = compareIdentifier(leftIdentifier, rightIdentifier);
      if (ordering != 0) {
        return ordering;
      }

      if (leftDot == -1) {
        if (rightDot == -1) {
          return 0;
        }
        return -1; // left has fewer identifiers
      }
      if (rightDot == -1) {
        return 1; // right has fewer identifiers
      }
      leftPos = leftDot + 1;
      rightPos = rightDot + 1;
    }
  }

  private static int compareIdentifier(final String left, final String right) {
    final boolean leftNumeric = isNumericIdentifier(left);
    final boolean rightNumeric = isNumericIdentifier(right);

    if (leftNumeric && rightNumeric) {
      // Numeric identifiers: compare by length first (longer = larger), then lexicographically
      if (left.length() < right.length()) {
        return -1;
      }
      if (left.length() > right.length()) {
        return 1;
      }
      return left.compareTo(right);
    } else if (leftNumeric) {
      return -1; // numeric < alphanumeric
    } else if (rightNumeric) {
      return 1; // alphanumeric > numeric
    }
    return left.compareTo(right);
  }

  private static boolean isNumericIdentifier(final String value) {
    for (int i = 0; i < value.length(); i++) {
      if (!isASCIIDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isASCIIDigit(final char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isASCIIAlphanumeric(final char c) {
    return isASCIIDigit(c) || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  // --- Accessors for testing ---

  long getMajor() {
    return major;
  }

  long getMinor() {
    return minor;
  }

  long getPatch() {
    return patch;
  }

  String getPrerelease() {
    return prerelease;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ParsedSemver)) {
      return false;
    }
    final ParsedSemver that = (ParsedSemver) o;
    return major == that.major
        && minor == that.minor
        && patch == that.patch
        && prerelease.equals(that.prerelease);
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(major);
    result = 31 * result + Long.hashCode(minor);
    result = 31 * result + Long.hashCode(patch);
    result = 31 * result + prerelease.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch + (prerelease.isEmpty() ? "" : "-" + prerelease);
  }
}
