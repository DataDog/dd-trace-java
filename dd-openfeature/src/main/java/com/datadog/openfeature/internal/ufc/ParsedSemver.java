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
        component = parseUnsignedDecimal(version, next, end);
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
    if (next == version.length()) {
      return new ParsedSemver(core, coreLength, "");
    }

    // Parse prerelease and/or build metadata in place; only the prerelease is copied.
    String prerelease = "";
    int buildStart;
    if (version.charAt(next) == '-') {
      final int prereleaseStart = next + 1;
      buildStart = version.indexOf('+', prereleaseStart);
      final int prereleaseEnd = buildStart == -1 ? version.length() : buildStart;
      if (!validSemverIdentifiers(version, prereleaseStart, prereleaseEnd, false)) {
        return INVALID;
      }
      prerelease = version.substring(prereleaseStart, prereleaseEnd);
      if (buildStart == -1) {
        return new ParsedSemver(core, coreLength, prerelease);
      }
    } else if (version.charAt(next) == '+') {
      buildStart = next;
    } else {
      return INVALID;
    }
    if (!validSemverIdentifiers(version, buildStart + 1, version.length(), true)) {
      return INVALID;
    }
    return new ParsedSemver(core, coreLength, prerelease);
  }

  private static final long MAX_UNSIGNED_DIV_10 = Long.divideUnsigned(-1L, 10);
  private static final long MAX_UNSIGNED_MOD_10 = Long.remainderUnsigned(-1L, 10);

  /**
   * Parses the ASCII digits {@code [start, end)} as an unsigned 64-bit integer without copying
   * them, like {@link Long#parseUnsignedLong(String)}.
   *
   * @throws NumberFormatException if the value does not fit in 64 unsigned bits
   */
  private static long parseUnsignedDecimal(final String value, final int start, final int end) {
    long result = 0;
    for (int i = start; i < end; i++) {
      final int digit = value.charAt(i) - '0';
      if (Long.compareUnsigned(result, MAX_UNSIGNED_DIV_10) > 0
          || (result == MAX_UNSIGNED_DIV_10 && digit > MAX_UNSIGNED_MOD_10)) {
        throw new NumberFormatException("unsigned 64-bit overflow");
      }
      result = result * 10 + digit;
    }
    return result;
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
    if (left.isEmpty()) {
      return 1; // release > prerelease
    }
    if (right.isEmpty()) {
      return -1; // prerelease < release
    }

    int leftPos = 0;
    int rightPos = 0;
    while (true) {
      // Compare the next identifier of each side in place
      final int leftDot = left.indexOf('.', leftPos);
      final int rightDot = right.indexOf('.', rightPos);
      final int leftEnd = leftDot == -1 ? left.length() : leftDot;
      final int rightEnd = rightDot == -1 ? right.length() : rightDot;

      final int ordering = compareIdentifier(left, leftPos, leftEnd, right, rightPos, rightEnd);
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
