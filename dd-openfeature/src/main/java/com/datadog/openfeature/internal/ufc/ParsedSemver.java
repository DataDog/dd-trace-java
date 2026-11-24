package com.datadog.openfeature.internal.ufc;

import java.util.Arrays;

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

  /**
   * Sentinel returned by {@link #compare(String, ParsedSemver)} when the version is not a valid
   * semantic version. No valid comparison returns it.
   */
  public static final int INVALID_ORDERING = Integer.MIN_VALUE;

  private static final long MAX_UNSIGNED_DIV_10 = Long.divideUnsigned(-1L, 10);

  private final long[] core; // unsigned, with omitted minor and patch normalized to zero
  private final int coreLength;
  private final String prerelease;

  ParsedSemver(final long[] core, final int coreLength, final String prerelease) {
    this.core = core;
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
    long[] core = new long[3];
    int coreSize = 0;
    int next = 0;
    while (true) {
      final int end = coreIdentifierEnd(version, next);
      if (end == -1) {
        return INVALID;
      }
      final long component;
      try {
        component = Long.parseUnsignedLong(version, next, end, 10);
      } catch (final NumberFormatException e) {
        return INVALID; // overflow
      }
      if (coreSize == core.length) {
        core = Arrays.copyOf(core, core.length * 2);
      }
      core[coreSize++] = component;
      next = end;
      if (next == version.length() || version.charAt(next) != '.') {
        break;
      }
      next++;
    }
    final int coreLength = Math.max(3, coreSize);
    // Validate prerelease and/or build metadata in place; only the prerelease is copied.
    final int prereleaseEnd = suffixPrereleaseEnd(version, next);
    if (prereleaseEnd == -1) {
      return INVALID;
    }
    final String prerelease =
        prereleaseEnd == next ? "" : version.substring(next + 1, prereleaseEnd);
    return new ParsedSemver(core, coreLength, prerelease);
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
   * Compares a version string with a parsed comparand by precedence, without parsing the string
   * into a {@link ParsedSemver}. The string is validated and compared without allocating: its core
   * components are read digit by digit and its prerelease is compared as a range of the string.
   * Build metadata is validated but ignored.
   *
   * <p>The result is exactly {@code compare(parse(version), comparand)} when {@code version} is
   * valid, and {@link #INVALID_ORDERING} when {@link #parse(String)} would reject it.
   *
   * @param version the version string to validate and compare
   * @param comparand the right operand
   * @return negative, zero or positive as for {@link #compare(ParsedSemver, ParsedSemver)}, or
   *     {@link #INVALID_ORDERING} if {@code version} is not a valid semantic version
   */
  public static int compare(final String version, final ParsedSemver comparand) {
    final int length = version.length();
    int ordering = 0;
    int component = 0;
    int next = 0;
    while (true) {
      // Read one core component with the leading zero and unsigned 64-bit overflow checks of parse
      if (next >= length || !isASCIIDigit(version.charAt(next))) {
        return INVALID_ORDERING;
      }
      long value = version.charAt(next++) - '0';
      if (value != 0) {
        while (next < length && isASCIIDigit(version.charAt(next))) {
          if (Long.compareUnsigned(value, MAX_UNSIGNED_DIV_10) > 0) {
            return INVALID_ORDERING; // overflow
          }
          final long shifted = value * 10;
          value = shifted + (version.charAt(next++) - '0');
          if (Long.compareUnsigned(value, shifted) < 0) {
            return INVALID_ORDERING; // overflow
          }
        }
      }
      if (ordering == 0) {
        ordering = Long.compareUnsigned(value, comparand.coreComponent(component));
      }
      component++;
      if (next == length || version.charAt(next) != '.') {
        break;
      }
      next++;
    }
    // Components the version omits are zero
    for (; ordering == 0 && component < comparand.coreLength; component++) {
      ordering = Long.compareUnsigned(0, comparand.core[component]);
    }

    final int prereleaseEnd = suffixPrereleaseEnd(version, next);
    if (prereleaseEnd == -1) {
      return INVALID_ORDERING;
    }
    if (ordering != 0) {
      return ordering;
    }
    final int prereleaseStart = prereleaseEnd == next ? next : next + 1;
    final String right = comparand.prerelease;
    return comparePrerelease(version, prereleaseStart, prereleaseEnd, right, 0, right.length());
  }

  /**
   * Validates the prerelease and build metadata that follow the core, which ends at {@code
   * coreEnd}. Returns the end of the prerelease, {@code coreEnd} if there is none, or -1 if the
   * suffix is invalid.
   */
  private static int suffixPrereleaseEnd(final String version, final int coreEnd) {
    final int length = version.length();
    if (coreEnd == length) {
      return coreEnd;
    }
    final int buildStart;
    final int prereleaseEnd;
    if (version.charAt(coreEnd) == '-') {
      buildStart = version.indexOf('+', coreEnd + 1);
      prereleaseEnd = buildStart == -1 ? length : buildStart;
      if (!validSemverIdentifiers(version, coreEnd + 1, prereleaseEnd, false)) {
        return -1;
      }
    } else if (version.charAt(coreEnd) == '+') {
      buildStart = coreEnd;
      prereleaseEnd = coreEnd;
    } else {
      return -1;
    }
    if (buildStart != -1 && !validSemverIdentifiers(version, buildStart + 1, length, true)) {
      return -1;
    }
    return prereleaseEnd;
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
    return index < coreLength ? core[index] : 0;
  }

  /**
   * Returns the number of significant core components, excluding trailing zero components that
   * {@link #compare} treats as equal to omitted components. This keeps {@link #equals} and {@link
   * #hashCode} consistent with precedence comparison so that versions like {@code 1.2.3} and {@code
   * 1.2.3.0} are equal and hash identically.
   */
  private int effectiveCoreLength() {
    int length = coreLength;
    while (length > 0 && core[length - 1] == 0L) {
      length--;
    }
    return length;
  }

  /**
   * Validates dot-separated identifiers. Permits leading zeros for build metadata only; numeric
   * prerelease identifiers reject them.
   */
  private static boolean validSemverIdentifiers(
      final String value, final int start, final int end, final boolean allowLeadingZeros) {
    int identifierStart = start;
    boolean identifierNumeric = true;
    for (int i = start; i <= end; i++) {
      if (i == end || value.charAt(i) == '.') {
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
    return comparePrerelease(left, 0, left.length(), right, 0, right.length());
  }

  /**
   * Compares the prereleases {@code left[leftStart, leftEnd)} and {@code right[rightStart,
   * rightEnd)}, where an empty range is a release.
   */
  private static int comparePrerelease(
      final String left,
      final int leftStart,
      final int leftEnd,
      final String right,
      final int rightStart,
      final int rightEnd) {
    if (leftStart == leftEnd) {
      return rightStart == rightEnd ? 0 : 1; // release > prerelease
    }
    if (rightStart == rightEnd) {
      return -1; // prerelease < release
    }

    int leftPos = leftStart;
    int rightPos = rightStart;
    while (true) {
      // Compare the next identifier of each side in place
      final int leftDot = indexOfDot(left, leftPos, leftEnd);
      final int rightDot = indexOfDot(right, rightPos, rightEnd);
      final int leftIdEnd = leftDot == -1 ? leftEnd : leftDot;
      final int rightIdEnd = rightDot == -1 ? rightEnd : rightDot;

      final int ordering = compareIdentifier(left, leftPos, leftIdEnd, right, rightPos, rightIdEnd);
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

  /** Returns the index of the first '.' in {@code value[start, end)}, or -1 if there is none. */
  private static int indexOfDot(final String value, final int start, final int end) {
    final int dot = value.indexOf('.', start);
    return dot < end ? dot : -1;
  }

  /**
   * Compares the identifiers {@code left[leftStart, leftEnd)} and {@code right[rightStart,
   * rightEnd)}.
   */
  private static int compareIdentifier(
      final String left,
      final int leftStart,
      final int leftEnd,
      final String right,
      final int rightStart,
      final int rightEnd) {
    final boolean leftNumeric = isNumericIdentifier(left, leftStart, leftEnd);
    final boolean rightNumeric = isNumericIdentifier(right, rightStart, rightEnd);
    final int leftLength = leftEnd - leftStart;
    final int rightLength = rightEnd - rightStart;

    if (leftNumeric && rightNumeric) {
      // Numeric identifiers: compare by length first (longer = larger), then lexicographically
      if (leftLength != rightLength) {
        return leftLength < rightLength ? -1 : 1;
      }
    } else if (leftNumeric) {
      return -1; // numeric < alphanumeric
    } else if (rightNumeric) {
      return 1; // alphanumeric > numeric
    }
    // Lexicographic by character, then by length, as String.compareTo
    final int length = Math.min(leftLength, rightLength);
    for (int i = 0; i < length; i++) {
      final char l = left.charAt(leftStart + i);
      final char r = right.charAt(rightStart + i);
      if (l != r) {
        return l - r;
      }
    }
    return leftLength - rightLength;
  }

  private static boolean isNumericIdentifier(final String value, final int start, final int end) {
    for (int i = start; i < end; i++) {
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
    return core[0];
  }

  long getMinor() {
    return core[1];
  }

  long getPatch() {
    return core[2];
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
    // Delegate to compare so equality matches precedence (SEMVER_EQ) and sorted collections, even
    // when versions differ only by trailing zero components such as 1.2.3 and 1.2.3.0.
    return compare(this, (ParsedSemver) o) == 0;
  }

  @Override
  public int hashCode() {
    // Hash over the trailing-zero-stripped core so that compare-equal versions hash identically.
    final int effectiveLength = effectiveCoreLength();
    int result = 1;
    for (int i = 0; i < effectiveLength; i++) {
      result = 31 * result + Long.hashCode(core[i]);
    }
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
