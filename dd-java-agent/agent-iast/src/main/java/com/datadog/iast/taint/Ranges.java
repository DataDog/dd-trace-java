package com.datadog.iast.taint;

import static com.datadog.iast.model.Range.NOT_MARKED;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Utilities to work with {@link Range} instances. */
public final class Ranges {

  public static final Range[] EMPTY = new Range[0];
  public static final RangesProvider<?> EMPTY_PROVIDER =
      new RangesProvider<Object>() {
        @Override
        public int rangeCount() {
          return 0;
        }

        @Override
        public int size() {
          return 0;
        }

        @Override
        public Object value(final int index) {
          throw new UnsupportedOperationException("empty provider");
        }

        @Override
        public Range[] ranges(final Object value) {
          throw new UnsupportedOperationException("empty provider");
        }
      };

  private Ranges() {}

  public static Range[] forString(
      final @Nonnull String obj, final @Nonnull Source source, final int mark) {
    return new Range[] {new Range(0, obj.length(), source, mark)};
  }

  public static Range[] forObject(final @Nonnull Source source, final int mark) {
    return new Range[] {new Range(0, Integer.MAX_VALUE, source, mark)};
  }

  public static void copyShift(
      final @Nonnull Range[] src, final @Nonnull Range[] dst, final int dstPos, final int shift) {
    if (shift == 0) {
      System.arraycopy(src, 0, dst, dstPos, src.length);
    } else {
      for (int iSrc = 0, iDst = dstPos; iSrc < src.length; iSrc++, iDst++) {
        dst[iDst] = src[iSrc].shift(shift);
      }
    }
  }

  public static Range[] mergeRanges(
      final int offset, @Nonnull final Range[] rangesLeft, @Nonnull final Range[] rangesRight) {
    final int nRanges = rangesLeft.length + rangesRight.length;
    final Range[] ranges = new Range[nRanges];
    if (rangesLeft.length > 0) {
      System.arraycopy(rangesLeft, 0, ranges, 0, rangesLeft.length);
    }
    if (rangesRight.length > 0) {
      Ranges.copyShift(rangesRight, ranges, rangesLeft.length, offset);
    }
    return ranges;
  }

  public static <E> RangesProvider<E> rangesProviderFor(
      @Nonnull final TaintedObjects to, @Nullable final E[] items) {
    if (items == null || items.length == 0) {
      return (RangesProvider<E>) EMPTY_PROVIDER;
    }
    return new ArrayProvider<>(items, to);
  }

  public static <E> RangesProvider<E> rangesProviderFor(
      @Nonnull final TaintedObjects to, @Nullable final E item) {
    if (item == null) {
      return (RangesProvider<E>) EMPTY_PROVIDER;
    }
    return new SingleProvider<>(item, to);
  }

  public static <E> RangesProvider<E> rangesProviderFor(
      @Nonnull final TaintedObjects to, @Nullable final List<E> items) {
    if (items == null || items.isEmpty()) {
      return (RangesProvider<E>) EMPTY_PROVIDER;
    }
    return new ListProvider<>(items, to);
  }

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

  public interface RangesProvider<E> {
    int rangeCount();

    int size();

    E value(final int index);

    Range[] ranges(final E value);
  }

  private abstract static class IterableProvider<E, LIST> implements RangesProvider<E> {
    private final LIST items;
    private final Map<E, Range[]> ranges;
    private final int rangeCount;

    private IterableProvider(@Nonnull final LIST items, @Nonnull final TaintedObjects to) {
      this.items = items;
      final int length = size(items);
      Map<E, Range[]> ranges = null;
      int rangeCount = 0;
      for (int i = 0; i < length; i++) {
        final E item = item(items, i);
        if (item != null) {
          final TaintedObject tainted = to.get(item);
          if (tainted != null) {
            final Range[] taintedRanges = tainted.getRanges();
            rangeCount += taintedRanges.length;
            if (ranges == null) {
              ranges = new HashMap<>(length);
            }
            ranges.put(item, taintedRanges);
          }
        }
      }
      this.ranges = ranges;
      this.rangeCount = rangeCount;
    }

    @Override
    public int rangeCount() {
      return rangeCount;
    }

    @Override
    public E value(final int index) {
      return item(items, index);
    }

    @Override
    public Range[] ranges(final E value) {
      return ranges == null ? null : ranges.get(value);
    }

    @Override
    public int size() {
      return size(items);
    }

    protected abstract int size(@Nonnull final LIST items);

    protected abstract E item(@Nonnull final LIST items, final int index);
  }

  private static class SingleProvider<E> implements RangesProvider<E> {
    private final E value;
    private final TaintedObject tainted;

    private SingleProvider(@Nonnull final E value, @Nonnull final TaintedObjects to) {
      this.value = value;
      tainted = to.get(value);
    }

    @Override
    public int rangeCount() {
      return tainted == null ? 0 : tainted.getRanges().length;
    }

    @Override
    public int size() {
      return 1;
    }

    @Override
    public E value(int index) {
      return index == 0 ? value : null;
    }

    @Override
    public Range[] ranges(E value) {
      return value == this.value && tainted != null ? tainted.getRanges() : null;
    }
  }

  private static class ArrayProvider<E> extends IterableProvider<E, E[]> {

    private ArrayProvider(@Nonnull final E[] items, @Nonnull final TaintedObjects to) {
      super(items, to);
    }

    @Override
    protected int size(@Nonnull final E[] items) {
      return items.length;
    }

    @Override
    protected E item(@Nonnull final E[] items, final int index) {
      return items[index];
    }
  }

  private static class ListProvider<E> extends IterableProvider<E, List<E>> {

    private ListProvider(@Nonnull final List<E> items, @Nonnull final TaintedObjects to) {
      super(items, to);
    }

    @Override
    protected int size(@Nonnull final List<E> items) {
      return items.size();
    }

    @Override
    protected E item(@Nonnull final List<E> items, final int index) {
      return items.get(index);
    }
  }

  public static Range createIfDifferent(Range range, int start, int length) {
    if (start != range.getStart() || length != range.getLength()) {
      return new Range(start, length, range.getSource(), range.getMarks());
    } else {
      return range;
    }
  }

  static int calculateSubstringSkippedRanges(int offset, int length, @Nonnull Range[] ranges) {
    // calculate how many skipped ranges are there
    int skippedRanges = 0;
    for (int rangeIndex = 0; rangeIndex < ranges.length; rangeIndex++) {
      final Range rangeSelf = ranges[rangeIndex];
      if (rangeSelf.getStart() + rangeSelf.getLength() <= offset) {
        skippedRanges++;
      } else {
        break;
      }
    }

    for (int rangeIndex = ranges.length - 1; rangeIndex >= 0; rangeIndex--) {
      final Range rangeSelf = ranges[rangeIndex];
      if (rangeSelf.getStart() - offset >= length) {
        skippedRanges++;
      } else {
        break;
      }
    }

    return skippedRanges;
  }

  public static Range[] getNotMarkedRanges(final Range[] ranges, final int mark) {
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
}
