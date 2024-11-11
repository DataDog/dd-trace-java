package com.datadog.iast.util;

import com.datadog.iast.model.Range;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import javax.annotation.Nullable;

/**
 * The range builder is an optimized data structure to deal with ranges in propagation and evidence
 * construction operations. The implementation is a hybrid of an array based list (for operations
 * dealing with ranges one by one) and a linked list (for operations dealing with arrays of ranges).
 *
 * <p>This implementation is subject to the following characteristics:
 *
 * <ol>
 *   <li>The number of items in the final array MUST be limited by the threshold.
 *   <li>Array allocations SHOULD be minimized (ideally only on final array creation).
 *   <li>The internal state MAY go over threshold but the final array MUST NOT.
 * </ol>
 */
public class RangeBuilder {

  private final int maxSize;
  private final int arrayChunkSize;
  private int size;
  @Nullable protected Entry head;
  @Nullable protected Entry tail;

  public RangeBuilder() {
    this(TaintedObject.MAX_RANGE_COUNT);
  }

  public RangeBuilder(final int maxSize) {
    this(
        maxSize,
        Math.min(ConfigDefaults.DEFAULT_IAST_MAX_RANGE_COUNT, Config.get().getIastMaxRangeCount()));
  }

  public RangeBuilder(final int maxSize, final int arrayChunkSize) {
    this.maxSize = maxSize;
    this.arrayChunkSize = arrayChunkSize;
    this.head = null;
    this.tail = null;
    this.size = 0;
  }

  public boolean add(final Range range) {
    return add(range, 0);
  }

  public boolean add(final Range range, final int offset) {
    if (size >= maxSize) {
      return false;
    }
    if (head == null) {
      addNewEntry(new SingleEntry(range.shift(offset)));
    } else {
      final ArrayEntry entry;
      if (tail instanceof ArrayEntry && tail.size() < arrayChunkSize) {
        entry = (ArrayEntry) tail;
      } else {
        entry = new ArrayEntry(arrayChunkSize);
        addNewEntry(entry);
      }
      entry.add(range.shift(offset));
    }
    size += 1;
    return true;
  }

  public boolean add(final Range[] ranges) {
    return add(ranges, 0);
  }

  public boolean add(final Range[] ranges, final int offset) {
    if (size >= maxSize) {
      return false;
    }
    if (ranges.length == 0) {
      return true;
    }
    if (ranges.length == 1) {
      return add(ranges[0], offset);
    }

    if (tail instanceof ArrayEntry && ranges.length <= (arrayChunkSize - tail.size())) {
      // compact intermediate ranges
      final ArrayEntry entry = (ArrayEntry) tail;
      for (Range range : ranges) {
        entry.add(range.shift(offset));
      }
      size += ranges.length;
      return true;
    }

    // we might go over the max but, it's OK (better not to generate a new array)
    final FixedArrayEntry entry = new FixedArrayEntry(ranges, offset);
    addNewEntry(entry);
    size += entry.size();
    return true;
  }

  public Range[] toArray() {
    if (head == null) {
      return Ranges.EMPTY;
    }
    if (size == head.size()) {
      return head.toArray();
    }
    final Range[] result = new Range[Math.min(maxSize, size)];
    int offset = 0;
    Entry entry = head;
    while (entry != null && offset < result.length) {
      offset += entry.arrayCopy(result, offset);
      entry = entry.next;
    }
    return result;
  }

  public boolean isFull() {
    return size >= maxSize;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public int size() {
    return size;
  }

  protected void addNewEntry(final Entry entry) {
    if (head == null || tail == null) {
      head = entry;
    } else {
      tail.next = entry;
    }
    tail = entry;
  }

  protected abstract static class Entry {
    @Nullable protected Entry next;

    protected abstract int size();

    protected abstract Range[] toArray();

    protected abstract int arrayCopy(Range[] result, int start);
  }

  /** Optimized case for single range scenarios */
  protected static class SingleEntry extends Entry {
    protected final Range range;

    protected SingleEntry(Range range) {
      this.range = range;
    }

    @Override
    protected int size() {
      return 1;
    }

    @Override
    protected Range[] toArray() {
      return new Range[] {range};
    }

    @Override
    protected int arrayCopy(final Range[] result, final int start) {
      if (start < 0 || start >= result.length) {
        return 0;
      }
      result[start] = range;
      return 1;
    }
  }

  /** Array buffer of entries */
  protected static class ArrayEntry extends Entry {
    protected final Range[] ranges;
    protected int size;

    protected ArrayEntry(final int size) {
      this.ranges = new Range[size];
      this.size = 0;
    }

    protected void add(final Range range) {
      ranges[size++] = range;
    }

    @Override
    protected int size() {
      return size;
    }

    @Override
    protected Range[] toArray() {
      if (size == ranges.length) {
        return ranges;
      }
      final Range[] result = new Range[size];
      arrayCopy(result, 0);
      return result;
    }

    @Override
    protected int arrayCopy(final Range[] result, final int start) {
      final int length = Math.min(size, result.length - start);
      if (start < 0 || length <= 0) {
        return 0;
      }
      System.arraycopy(ranges, 0, result, start, length);
      return length;
    }
  }

  /** Fixed size array with an offset */
  protected static class FixedArrayEntry extends Entry {
    protected final Range[] ranges;
    protected final int offset;

    protected FixedArrayEntry(final Range[] ranges, final int offset) {
      this.ranges = ranges;
      this.offset = offset;
    }

    @Override
    protected int size() {
      return ranges.length;
    }

    @Override
    protected Range[] toArray() {
      if (offset == 0) {
        return ranges;
      }
      final Range[] result = new Range[ranges.length];
      arrayCopy(result, 0);
      return result;
    }

    @Override
    protected int arrayCopy(final Range[] result, final int start) {
      final int length = Math.min(result.length - start, ranges.length);
      if (start < 0 || length <= 0) {
        return 0;
      }
      Ranges.copyShift(ranges, result, start, offset, length);
      return length;
    }
  }
}
