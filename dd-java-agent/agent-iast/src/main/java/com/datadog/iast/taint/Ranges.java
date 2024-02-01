package com.datadog.iast.taint;

import static com.datadog.iast.taint.TaintedObject.MAX_RANGE_COUNT;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.util.HttpHeader;
import datadog.trace.api.iast.SourceTypes;
import java.util.ArrayList;
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

  public interface RangesProvider<E> {
    int rangeCount();

    int size();

    @Nullable
    E value(final int index);

    @Nullable
    Range[] ranges(final E value);
  }

  private abstract static class IterableProvider<E, LIST> implements RangesProvider<E> {
    private final LIST items;
    @Nullable private final Map<E, Range[]> ranges;
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

    @Nullable
    @Override
    public E value(final int index) {
      return item(items, index);
    }

    @Nullable
    @Override
    public Range[] ranges(final E value) {
      return ranges == null ? null : ranges.get(value);
    }

    @Override
    public int size() {
      return size(items);
    }

    protected abstract int size(@Nonnull final LIST items);

    @Nullable
    protected abstract E item(@Nonnull final LIST items, final int index);
  }

  private static class SingleProvider<E> implements RangesProvider<E> {
    private final E value;
    @Nullable private final TaintedObject tainted;

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

    @Nullable
    @Override
    public E value(int index) {
      return index == 0 ? value : null;
    }

    @Nullable
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

    @Nullable
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

    @Nullable
    @Override
    protected E item(@Nonnull final List<E> items, final int index) {
      return items.get(index);
    }
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

  public static class RangeList {
    private final ArrayList<Range> delegate = new ArrayList<>();
    private int remaining;

    public RangeList() {
      this(MAX_RANGE_COUNT);
    }

    public RangeList(final int maxSize) {
      this.remaining = maxSize;
    }

    public boolean add(final Range item) {
      if (remaining > 0 && delegate.add(item)) {
        remaining--;
        return true;
      }
      return false;
    }

    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    public boolean isFull() {
      return remaining == 0;
    }

    public Range[] toArray() {
      return delegate.toArray(new Range[0]);
    }
  }
}
