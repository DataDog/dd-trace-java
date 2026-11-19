package com.datadog.openfeature.internal.evaluation;

import com.datadog.openfeature.internal.ufc.ParsedSemver;
import java.util.Arrays;

/**
 * Remembers the SemVer parse of each attribute value during one evaluation, so a flag with many
 * SemVer conditions on the same attribute parses its value once. Invalid versions are remembered
 * too. One instance serves a single evaluation on a single thread.
 */
final class SemverParseCache {
  private String firstValue;
  private ParsedSemver firstParsed;
  private String[] moreValues;
  private ParsedSemver[] moreParsed;
  private int moreSize;

  /**
   * Returns the parse of {@code value}, or {@code null} if it is not a valid semantic version.
   *
   * @param value the attribute value, not null
   */
  ParsedSemver parse(final String value) {
    // String.equals checks identity first, so a repeated lookup of the same instance stays cheap.
    if (firstValue != null) {
      if (firstValue.equals(value)) {
        return firstParsed;
      }
      for (int i = 0; i < moreSize; i++) {
        final String cached = moreValues[i];
        if (cached.equals(value)) {
          return moreParsed[i];
        }
      }
    }
    final ParsedSemver parsed = ParsedSemver.parse(value);
    if (firstValue == null) {
      firstValue = value;
      firstParsed = parsed;
    } else {
      if (moreValues == null) {
        moreValues = new String[2];
        moreParsed = new ParsedSemver[2];
      } else if (moreSize == moreValues.length) {
        moreValues = Arrays.copyOf(moreValues, moreSize * 2);
        moreParsed = Arrays.copyOf(moreParsed, moreSize * 2);
      }
      moreValues[moreSize] = value;
      moreParsed[moreSize++] = parsed;
    }
    return parsed;
  }

  /** The number of values parsed so far, for tests. */
  int parses() {
    return firstValue == null ? 0 : 1 + moreSize;
  }
}
