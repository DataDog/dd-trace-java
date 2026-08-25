package datadog.trace.api.featureflag.ufc.v1;

/**
 * ParsedSemver is the language-neutral representation of the SemVer subset used by FFE. Owning this
 * parser and comparator keeps behavior consistent across SDKs and lets configuration preprocessing
 * cache comparands instead of reparsing them during every evaluation.
 *
 * <p>Core identifiers (major, minor, patch) are limited to unsigned 64-bit integers, stored in
 * {@code long} fields and compared with unsigned semantics. Numeric prerelease identifiers may be
 * arbitrarily large and are compared by length-then-lexicographic order. Build metadata is
 * validated during parsing but not retained because it does not affect SemVer precedence.
 */
public final class ParsedSemver {

  /** Sentinel returned by {@link #parse(String)} when the input is not a valid semantic version. */
  public static final ParsedSemver INVALID = null;

  private static final int MAX_CORE_PARTS = 5;

  private final long major; // unsigned
  private final long minor; // unsigned
  private final long patch; // unsigned
  private final long fourth; // unsigned
  private final long fifth; // unsigned
  private final int coreLength;
  private final String prerelease;

  ParsedSemver(
      final long major,
      final long minor,
      final long patch,
      final long fourth,
      final long fifth,
      final int coreLength,
      final String prerelease) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.fourth = fourth;
    this.fifth = fifth;
    this.coreLength = coreLength;
    this.prerelease = prerelease;
  }

  /**
   * Parses a semantic version string using the syntax supported by FFE.
   *
   * @param version the version string to parse
   * @return a {@link ParsedSemver} instance, or {@code null} if the input is not a valid semantic
   *     version
   */
  public static ParsedSemver parse(final String version) {
    long major = 0;
    long minor = 0;
    long patch = 0;
    long fourth = 0;
    long fifth = 0;
    int coreSize = 0;
    int next = 0;
    while (true) {
      final int end = coreIdentifierEnd(version, next);
      if (end == -1) {
        return INVALID;
      }
      final long component;
      try {
        component = Long.parseUnsignedLong(version.substring(next, end));
      } catch (final NumberFormatException e) {
        return INVALID; // overflow
      }
      switch (coreSize++) {
        case 0:
          major = component;
          break;
        case 1:
          minor = component;
          break;
        case 2:
          patch = component;
          break;
        case 3:
          fourth = component;
          break;
        case 4:
          fifth = component;
          break;
        default:
          return INVALID;
      }
      next = end;
      if (next == version.length() || version.charAt(next) != '.') {
        break;
      }
      if (coreSize == MAX_CORE_PARTS) {
        return INVALID;
      }
      next++;
    }
    final int coreLength = Math.max(3, coreSize);
    if (next == version.length()) {
      return new ParsedSemver(major, minor, patch, fourth, fifth, coreLength, "");
    }

    // Parse prerelease and/or build metadata.
    String remainder = version.substring(next);
    String prerelease = "";
    if (remainder.charAt(0) == '-') {
      remainder = remainder.substring(1);
      final int buildStart = remainder.indexOf('+');
      if (buildStart == -1) {
        if (!validSemverIdentifiers(remainder, false)) {
          return INVALID;
        }
        return new ParsedSemver(major, minor, patch, fourth, fifth, coreLength, remainder);
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
    return new ParsedSemver(major, minor, patch, fourth, fifth, coreLength, prerelease);
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
    final int coreLength = Math.max(left.coreLength, right.coreLength);
    for (int i = 0; i < coreLength; i++) {
      final int cmp = Long.compareUnsigned(left.coreComponent(i), right.coreComponent(i));
      if (cmp != 0) {
        return cmp;
      }
    }
    return comparePrerelease(left.prerelease, right.prerelease);
  }

  /**
   * Finds the end of a core version identifier (major, minor, or patch), rejecting leading zeros
   * except for the value zero itself.
   */
  private static int coreIdentifierEnd(final String version, final int start) {
    if (start >= version.length() || !isASCIIDigit(version.charAt(start))) {
      return -1;
    }
    if (version.charAt(start) == '0') {
      return start + 1;
    }

    int end = start;
    while (end < version.length() && isASCIIDigit(version.charAt(end))) {
      end++;
    }
    return end;
  }

  private long coreComponent(final int index) {
    switch (index) {
      case 0:
        return major;
      case 1:
        return minor;
      case 2:
        return patch;
      case 3:
        return fourth;
      case 4:
        return fifth;
      default:
        return 0;
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
        && fourth == that.fourth
        && fifth == that.fifth
        && coreLength == that.coreLength
        && prerelease.equals(that.prerelease);
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(major);
    result = 31 * result + Long.hashCode(minor);
    result = 31 * result + Long.hashCode(patch);
    result = 31 * result + Long.hashCode(fourth);
    result = 31 * result + Long.hashCode(fifth);
    result = 31 * result + coreLength;
    return 31 * result + prerelease.hashCode();
  }

  @Override
  public String toString() {
    final StringBuilder value = new StringBuilder();
    for (int i = 0; i < coreLength; i++) {
      if (i > 0) {
        value.append('.');
      }
      value.append(Long.toUnsignedString(coreComponent(i)));
    }
    return value.append(prerelease.isEmpty() ? "" : "-" + prerelease).toString();
  }
}
