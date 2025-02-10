package com.datadog.iast.util;

import com.datadog.iast.model.Range;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObjects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class StringUtils {

  private StringUtils() {}

  /**
   * Checks if the string ends with the selected suffix ignoring case. Note that this method does
   * not take locale into account.
   */
  public static boolean endsWithIgnoreCase(
      @Nonnull final String value, @Nonnull final String suffix) {
    if (value.length() < suffix.length()) {
      return false;
    }
    if (suffix.isEmpty()) {
      return true;
    }
    final int offset = value.length() - suffix.length();
    return value.regionMatches(true, offset, suffix, 0, suffix.length());
  }

  /**
   * Performs a substring of the selected string taking care of leading and trailing whitespaces.
   */
  @Nonnull
  public static String substringTrim(@Nonnull final String value, int start, int end) {
    if (start >= end) {
      return "";
    }
    while (start < end && Character.isWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return start >= end ? "" : value.substring(start, end);
  }

  /** Returns how many leading whitespaces are between the start and the end of the string */
  @Nonnull
  public static int leadingWhitespaces(@Nonnull final String value, int start, int end) {
    int whitespaces = start;
    while (whitespaces < end && Character.isWhitespace(value.charAt(whitespaces))) {
      whitespaces++;
    }
    return whitespaces;
  }

  /**
   * Returns the string replaced with the regex and the values tainted (if needed)
   *
   * @param taintedObjects the ctx object to save the range of the tainted values
   * @param target the string to be replaced
   * @param pattern the pattern to be replaced
   * @param replacement the replacement string
   * @param ranges the ranges of the string to be replaced
   * @param rangesInput the ranges of the input string
   * @param numOfReplacements the number of replacements to be made
   */
  @Nonnull
  public static String replaceAndTaint(
      @Nonnull TaintedObjects taintedObjects,
      @Nonnull final String target,
      Pattern pattern,
      String replacement,
      Range[] ranges,
      @Nullable Range[] rangesInput,
      int numOfReplacements) {
    if (numOfReplacements <= 0) {
      return target;
    }
    Matcher matcher = pattern.matcher(target);
    boolean result = matcher.find();
    if (result) {
      int offset = 0;
      RangeBuilder newRanges = new RangeBuilder();

      int firstRange = 0;
      int newLength = replacement.length();

      // In case there is a '\' or '$' in the replacement string we need to make a
      // quoteReplacement
      // If there is no '\' or '$' it will return the same string.
      String finalReplacement = Matcher.quoteReplacement(replacement);

      boolean canAddRange = true;
      StringBuffer sb = new StringBuffer();
      do {
        int start = matcher.start();
        int end = matcher.end();
        int diffLength = newLength - (end - start);

        boolean rangesAdded = false;
        while (firstRange < ranges.length && canAddRange) {
          Range range = ranges[firstRange];
          int rangeStart = range.getStart();
          int rangeEnd = rangeStart + range.getLength();
          // If the replaced value is between one range
          if (rangeStart <= start && rangeEnd >= end) {
            Range[] splittedRanges =
                Ranges.splitRanges(start, end, newLength, range, offset, diffLength);

            if (splittedRanges.length > 0 && splittedRanges[0].getLength() > 0) {
              canAddRange = newRanges.add(splittedRanges[0]);
            }

            if (rangesInput != null) {
              canAddRange = newRanges.add(rangesInput, start + offset);
              rangesAdded = true;
            }

            if (splittedRanges.length > 1 && splittedRanges[1].getLength() > 0) {
              canAddRange = newRanges.add(splittedRanges[1]);
            }

            firstRange++;
            break;
            // If the replaced value starts in the range and not end there
          } else if (rangeStart <= start && rangeEnd > start) {
            Range[] splittedRanges =
                Ranges.splitRanges(start, end, newLength, range, offset, diffLength);

            if (splittedRanges.length > 0 && splittedRanges[0].getLength() > 0) {
              canAddRange = newRanges.add(splittedRanges[0]);
            }

            if (rangesInput != null && !rangesAdded) {
              canAddRange = newRanges.add(rangesInput, start + offset);
              rangesAdded = true;
            }

            // If the replaced value ends in the range
          } else if (rangeEnd >= end) {
            Range[] splittedRanges =
                Ranges.splitRanges(start, end, newLength, range, offset, diffLength);

            if (rangesInput != null && !rangesAdded) {
              canAddRange = newRanges.add(rangesInput, start + offset);
              rangesAdded = true;
            }

            if (splittedRanges.length > 1 && splittedRanges[1].getLength() > 0) {
              canAddRange = newRanges.add(splittedRanges[1]);
            }

            firstRange++;
            break;
            // Middle ranges
          } else if (rangeStart >= start) {
            firstRange++;
            continue;
          } else {
            canAddRange = newRanges.add(range, rangeStart + offset);
          }

          firstRange++;
        }

        // In case there are no ranges
        if (rangesInput != null && !rangesAdded && canAddRange) {
          canAddRange = newRanges.add(rangesInput, start + offset);
        }

        matcher.appendReplacement(sb, finalReplacement);

        offset = diffLength;
        numOfReplacements--;
        if (numOfReplacements > 0) {
          result = matcher.find();
        }
      } while (result && numOfReplacements > 0);

      // In the case there is no tainted object
      if (firstRange < ranges.length && canAddRange) {
        for (int i = firstRange; i < ranges.length && canAddRange; i++) {
          canAddRange = newRanges.add(ranges[i], offset);
        }
      }

      matcher.appendTail(sb);
      String finalString = sb.toString();
      Range[] finalRanges = newRanges.toArray();
      if (finalRanges.length > 0) {
        taintedObjects.taint(finalString, finalRanges);
      }
      return finalString;
    }

    return target;
  }
}
