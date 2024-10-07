package com.datadog.iast.taint;

import static com.datadog.iast.taint.TaintedObject.MAX_RANGE_COUNT;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.util.HttpHeader;
import com.datadog.iast.util.RangeBuilder;
import com.datadog.iast.util.Ranged;
import datadog.trace.api.iast.SourceTypes;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Utilities to work with {@link Range} instances. */
public final class Ranges {

  public static final Range[] EMPTY = new Range[0];

  private Ranges() {}

  public static Range[] forCharSequence(
      final @Nonnull CharSequence obj, final @Nonnull Source source) {
    return forCharSequence(obj, source, NOT_MARKED);
  }

  public static Range[] forCharSequence(
      final @Nonnull CharSequence obj, final @Nonnull Source source, final int mark) {
    return new Range[] {new Range(0, obj.length(), source, mark)};
  }

  public static Range[] forObject(final @Nonnull Source source) {
    return forObject(source, NOT_MARKED);
  }

  public static Range[] forObject(final @Nonnull Source source, final int mark) {
    return new Range[] {new Range(0, Integer.MAX_VALUE, source, mark)};
  }

  @Nullable
  public static Range[] intersection(
      final @Nonnull Ranged targetRange, @Nonnull final Range[] ranges) {
    final Range last = ranges[ranges.length - 1];
    final int lastIndex = last.getStart() + last.getLength();

    final RangeBuilder targetRanges = new RangeBuilder(ranges.length);
    for (final Range range : ranges) {
      if (range.getStart() >= lastIndex) {
        break;
      }
      final Ranged intersection = targetRange.intersection(range);
      if (intersection != null) {
        targetRanges.add(
            new Range(
                intersection.getStart(),
                intersection.getLength(),
                range.getSource(),
                range.getMarks()));
      }
    }
    return targetRanges.isEmpty() ? null : targetRanges.toArray();
  }

  @Nullable
  public static Range findUnbound(@Nonnull final Range[] ranges) {
    if (ranges.length != 1) {
      return null;
    }
    final Range range = ranges[0];
    return range.getStart() == 0 && range.getLength() == Integer.MAX_VALUE ? range : null;
  }

  public static void copyShift(
      final @Nonnull Range[] src, final @Nonnull Range[] dst, final int dstPos, final int shift) {
    copyShift(src, dst, dstPos, shift, src.length);
  }

  public static void copyShift(
      final @Nonnull Range[] src,
      final @Nonnull Range[] dst,
      final int dstPos,
      final int shift,
      final int max) {
    final int srcLength = Math.min(max, src.length);
    if (srcLength <= 0) {
      return;
    }
    if (shift == 0) {
      System.arraycopy(src, 0, dst, dstPos, srcLength);
    } else {
      for (int iSrc = 0, iDst = dstPos; iSrc < srcLength; iSrc++, iDst++) {
        dst[iDst] = src[iSrc].shift(shift);
      }
    }
  }

  public static Range[] mergeRanges(
      final int offset, @Nonnull final Range[] rangesLeft, @Nonnull final Range[] rangesRight) {
    final long nRanges = rangesLeft.length + (long) rangesRight.length;
    final Range[] ranges = newArray(nRanges);
    int remaining = ranges.length;
    if (rangesLeft.length > 0) {
      final int count = Math.min(rangesLeft.length, remaining);
      System.arraycopy(rangesLeft, 0, ranges, 0, count);
      remaining -= count;
    }
    if (rangesRight.length > 0 && remaining > 0) {
      Ranges.copyShift(rangesRight, ranges, rangesLeft.length, offset, remaining);
    }
    return ranges;
  }

  @Nullable
  public static Range[] forSubstring(int offset, int length, final @Nonnull Range[] ranges) {

    int[] includedRangesInterval = getIncludedRangesInterval(offset, length, ranges);

    // No ranges in the interval
    if (includedRangesInterval[0] == -1) {
      return null;
    }
    final int firstRangeIncludedIndex = includedRangesInterval[0];
    final int lastRangeIncludedIndex =
        includedRangesInterval[1] != -1 ? includedRangesInterval[1] : ranges.length;
    final int newRagesSize = lastRangeIncludedIndex - firstRangeIncludedIndex;
    Range[] newRanges = new Range[newRagesSize];
    for (int rangeIndex = firstRangeIncludedIndex, newRangeIndex = 0;
        newRangeIndex < newRagesSize;
        rangeIndex++, newRangeIndex++) {
      Range range = ranges[rangeIndex];
      if (offset == 0 && range.getStart() + range.getLength() <= length) {
        newRanges[newRangeIndex] = range;
      } else {
        int newStart = range.getStart() - offset;
        int newLength = range.getLength();
        final int newEnd = newStart + newLength;
        if (newStart < 0) {
          newLength = newLength + newStart;
          newStart = 0;
        }
        if (newEnd > length) {
          newLength = length - newStart;
        }
        if (newLength > 0) {
          newRanges[newRangeIndex] =
              new Range(newStart, newLength, range.getSource(), range.getMarks());
        }
      }
    }

    return newRanges;
  }

  public static int[] getIncludedRangesInterval(
      int offset, int length, final @Nonnull Range[] ranges) {
    // index of the first included range
    int start = -1;
    // index of the first not included range
    int end = -1;
    for (int rangeIndex = 0; rangeIndex < ranges.length; rangeIndex++) {
      final Range rangeSelf = ranges[rangeIndex];
      if (rangeSelf.getStart() < offset + length
          && rangeSelf.getStart() + rangeSelf.getLength() > offset) {
        if (start == -1) {
          start = rangeIndex;
        }
      } else if (start != -1) {
        end = rangeIndex;
        break;
      }
    }
    return new int[] {start, end};
  }

  @Nonnull
  public static Range highestPriorityRange(@Nonnull final Range[] ranges) {
    /*
     * This approach is better but not completely correct ideally the highest priority should use the following patterns:
     * 1) Range coming from the request with no mark related with the vulnerability
     * 2) Range coming from other origins with no mark related with the vulnerability
     * 3) Range coming from the request with a mark related with the vulnerability
     * 4) Range coming from other origins with a mark related with the vulnerability
     *
     * To improve performance we decide to simplify the algorithm:
     * 1) First range with no mark
     * 2) Fist Range
     */
    for (Range range : ranges) {
      if (range.getMarks() == NOT_MARKED) {
        return range;
      }
    }
    return ranges[0];
  }

  /**
   * Checks if all ranges are coming from any header, in case no ranges are provided it will return
   * {@code true}
   */
  public static boolean allRangesFromAnyHeader(@Nonnull final Range[] ranges) {
    for (Range range : ranges) {
      final Source source = range.getSource();
      if (source.getOrigin() != SourceTypes.REQUEST_HEADER_VALUE) {
        return false;
      }
    }
    return true;
  }

  /** Checks if a range is coming from the header */
  public static boolean rangeFromHeader(@Nonnull final String header, @Nonnull final Range range) {
    final Source source = range.getSource();
    if (source.getOrigin() != SourceTypes.REQUEST_HEADER_VALUE) {
      return false;
    }
    if (!header.equalsIgnoreCase(source.getName())) {
      return false;
    }
    return true;
  }

  /**
   * Checks if all ranges are coming from the header, in case no ranges are provided it will return
   * {@code true}
   */
  public static boolean allRangesFromHeader(
      @Nonnull final String header, @Nonnull final Range[] ranges) {
    for (Range range : ranges) {
      if (!rangeFromHeader(header, range)) {
        return false;
      }
    }
    return true;
  }

  /** @see #allRangesFromHeader(String, Range[]) */
  public static boolean allRangesFromHeader(
      @Nonnull final HttpHeader header, @Nonnull final Range[] ranges) {
    return allRangesFromHeader(header.name, ranges);
  }

  public static Range[] newArray(final long size) {
    return new Range[size > MAX_RANGE_COUNT ? MAX_RANGE_COUNT : (int) size];
  }

  /** Merge the new ranges maintaining order (it assumes that both arrays are already sorted) */
  public static Range[] mergeRangesSorted(final Range[] leftRanges, final Range[] rightRanges) {
    if (leftRanges.length == 0) {
      return rightRanges;
    }
    if (rightRanges.length == 0) {
      return leftRanges;
    }
    int rightIndex = 0;
    Range rightRange = rightRanges[0];
    final Range[] result = new Range[rightRanges.length + leftRanges.length];
    for (int leftIndex = 0; leftIndex < leftRanges.length; leftIndex++) {
      final Range leftRange = leftRanges[leftIndex];
      if (rightRange == null) {
        result[leftIndex + rightRanges.length] = leftRange;
      } else {
        if (rightRange.getStart() < leftRange.getStart()) {
          result[leftIndex + rightIndex] = rightRange;
          rightIndex++;
          rightRange = rightIndex >= rightRanges.length ? null : rightRanges[rightIndex];
        }
        result[leftIndex + rightIndex] = leftRange;
      }
    }
    for (; rightIndex < rightRanges.length; rightIndex++) {
      result[leftRanges.length + rightIndex] = rightRanges[rightIndex];
    }
    return result;
  }

  @Nullable
  public static Range[] getNotMarkedRanges(@Nullable final Range[] ranges, final int mark) {
    if (ranges == null) {
      return null;
    }
    int markedRangesNumber = 0;
    for (Range range : ranges) {
      if (range.isMarked(mark)) {
        markedRangesNumber++;
      }
    }
    if (markedRangesNumber == 0) {
      return ranges;
    }
    if (markedRangesNumber == ranges.length) {
      return null;
    }
    Range[] notMarkedRanges = new Range[ranges.length - markedRangesNumber];
    int notMarkedRangesIndex = 0;
    for (Range range : ranges) {
      if (!range.isMarked(mark)) {
        notMarkedRanges[notMarkedRangesIndex] = range;
        notMarkedRangesIndex++;
      }
    }
    return notMarkedRanges;
  }

  public static Range copyWithPosition(final Range range, final int offset, final int length) {
    return new Range(offset, length, range.getSource(), range.getMarks());
  }

  /** Returns a new array of ranges with the character sequences replaced applied */
  public static Range[] forReplaceCharSeq(
      final String self,
      final CharSequence oldCharSeq,
      final CharSequence newCharSeq,
      final Range[] ranges) {
    RangeBuilder newRanges = new RangeBuilder(ranges.length * 2);
    // This range is used to have a reference to the current range modified
    Range currentRange = ranges[0];
    int lastRangeUsed = -1;
    int firstRange = 0;
    int offset = 0;
    int diffLength = newCharSeq.length() - oldCharSeq.length();
    for (int i = 0; i + oldCharSeq.length() < self.length() && firstRange < ranges.length; i++) {
      if (!self.substring(i, i + oldCharSeq.length()).contentEquals(oldCharSeq)) {
        continue;
      }

      // Move to the next valid range
      while (firstRange < ranges.length) {
        Range range = ranges[firstRange];
        if (range.getStart() <= i && range.getStart() + range.getLength() >= i) {
          if (firstRange > lastRangeUsed) {
            currentRange = range;
          }
          break;
        }
        if (firstRange > lastRangeUsed) {
          newRanges.add(currentRange);
        } else {
          newRanges.add(
              new Range(
                  range.getStart() + offset,
                  range.getLength(),
                  range.getSource(),
                  range.getMarks()));
        }
        firstRange++;
      }

      if (firstRange < ranges.length) {
        Range[] splittedRanges =
            splitRanges(
                i + offset,
                i + oldCharSeq.length() + offset,
                newCharSeq.length(),
                currentRange,
                offset,
                diffLength);
        if (splittedRanges.length == 1) {
          continue;
        }
        if (splittedRanges[0].getLength() > 0) {
          newRanges.add(splittedRanges[0]);
        }

        lastRangeUsed = firstRange;
        currentRange = splittedRanges[1];
      }

      offset += diffLength;
    }

    firstRange++;

    // In the case there is no tainted object
    if (firstRange < ranges.length) {
      for (int i = firstRange; i < ranges.length; i++) {
        newRanges.add(
            new Range(
                ranges[i].getStart() + offset,
                ranges[i].getLength(),
                ranges[i].getSource(),
                ranges[i].getMarks()));
      }
    } else {
      newRanges.add(currentRange);
    }

    return newRanges.toArray();
  }

  /**
   * Split the range in two taking into account the new length of the characters. In case start and
   * end are out of the range, it will return the range without splitting.
   *
   * @param start is the start of the character sequence
   * @param end is the end of the character sequence
   * @param newLength is the new length of the character sequence
   * @param range is the range to split
   * @param offset is the offset to apply to the range
   * @param diffLength is the difference between the new length and the old length
   */
  private static Range[] splitRanges(
      int start, int end, int newLength, Range range, int offset, int diffLength) {
    int rangeStart = range.getStart() + offset;
    int rangeEnd = rangeStart + range.getLength() + diffLength;

    if (rangeStart > end || rangeEnd < start || rangeStart > start && rangeEnd < end) {
      return new Range[] {range};
    }

    Range[] splittedRanges = new Range[2];
    int firstLength = start - rangeStart;
    int secondLength = range.getLength() - firstLength - newLength + diffLength;
    splittedRanges[0] = new Range(rangeStart, firstLength, range.getSource(), range.getMarks());
    splittedRanges[1] =
        new Range(rangeEnd - secondLength, secondLength, range.getSource(), range.getMarks());

    return splittedRanges;
  }
}
