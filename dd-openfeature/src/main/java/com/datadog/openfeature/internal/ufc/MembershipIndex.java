package com.datadog.openfeature.internal.ufc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A hash index over a {@code ONE_OF} or {@code NOT_ONE_OF} list, built once when the configuration
 * is parsed, so that a membership test costs O(1) instead of a scan of the list.
 *
 * <p>The index gives the same result as the scan in {@code FlagEvaluator}, which compares the
 * attribute with each element in list order:
 *
 * <ul>
 *   <li>A number attribute is compared numerically with each element. The first element that cannot
 *       be converted to a number (a non-numeric string, a boolean, a nested list or object, or
 *       {@code null}) throws, unless an earlier element matched.
 *   <li>A string or boolean attribute is compared numerically with each number element (the
 *       attribute must then parse as a number, else the scan throws at the first number element
 *       unless an earlier element matched), and as a string with each other element ({@code "true"}
 *       matches {@code true}, {@code "null"} matches {@code null}).
 * </ul>
 *
 * <p>When the result depends on an exception, or for another attribute type, {@link #lookup}
 * returns {@link #SCAN} and the caller runs the scan, which gives the old result, including any
 * exception.
 */
public final class MembershipIndex {
  /** Lists shorter than this keep the scan. */
  static final int MIN_INDEXED_SIZE = 2;

  public static final int NO_MATCH = 0;
  public static final int MATCH = 1;

  /** The index cannot decide: run the scan over the list. */
  public static final int SCAN = -1;

  private static final int NONE = Integer.MAX_VALUE;

  /** {@code String.valueOf} of each element that is not a number, to its first index. */
  private final Map<String, Integer> strings;

  /**
   * Each number element, and each numeric string before the first unconvertible element, to its
   * first index.
   */
  private final LongIntIndex numeric;

  /**
   * Each element that is a number, to its first index; the same table as {@link #numeric} when the
   * list has no numeric strings.
   */
  private final LongIntIndex numbers;

  /** The first element that does not convert to a number, or {@link #NONE}. */
  private final int firstUnconvertible;

  /** The first element that is a number, or {@link #NONE}. */
  private final int firstNumber;

  private MembershipIndex(final List<?> values) {
    final int size = values.size();
    this.strings = new HashMap<>(capacity(size));
    // Keys of the elements that convert to a number, then of the number elements, in list order.
    final long[] numericKeys = new long[size];
    final int[] numericIndexes = new int[size];
    int numericCount = 0;
    final long[] numberKeys = new long[size];
    final int[] numberIndexes = new int[size];
    int numberCount = 0;
    int unconvertible = NONE;
    for (int i = 0; i < size; i++) {
      final Object element = values.get(i);
      if (element instanceof Number) {
        final long key = key(((Number) element).doubleValue());
        numericKeys[numericCount] = key;
        numericIndexes[numericCount++] = i;
        numberKeys[numberCount] = key;
        numberIndexes[numberCount++] = i;
        continue;
      }
      this.strings.putIfAbsent(String.valueOf(element), i);
      if (unconvertible != NONE) {
        // The scan throws at the first unconvertible element: later elements never match.
        continue;
      }
      // The same conversion as the scan: Double.parseDouble(String.valueOf(element)).
      if (element == null || element instanceof Boolean) {
        unconvertible = i;
        continue;
      }
      try {
        numericKeys[numericCount] = key(Double.parseDouble(String.valueOf(element)));
        numericIndexes[numericCount++] = i;
      } catch (final NumberFormatException e) {
        unconvertible = i;
      }
    }
    this.numeric = LongIntIndex.of(numericKeys, numericIndexes, numericCount);
    // Without numeric strings, both tables hold the same keys and first indexes.
    this.numbers =
        numberCount == numericCount
            ? this.numeric
            : LongIntIndex.of(numberKeys, numberIndexes, numberCount);
    this.firstUnconvertible = unconvertible;
    this.firstNumber = numberCount == 0 ? NONE : numberIndexes[0];
  }

  /**
   * Builds the index for a condition value. The number elements must be finite, as the parser
   * guarantees: the scan matches a {@code NaN} element with a {@code NaN} attribute, the index does
   * not.
   *
   * @return the index, or {@code null} if the value is not a list or is too short to index.
   */
  @Nullable
  public static MembershipIndex build(@Nullable final Object value) {
    if (!(value instanceof List) || ((List<?>) value).size() < MIN_INDEXED_SIZE) {
      return null;
    }
    return new MembershipIndex((List<?>) value);
  }

  /**
   * Looks up a non-null attribute value.
   *
   * @return {@link #MATCH}, {@link #NO_MATCH}, or {@link #SCAN} if the caller must scan the list.
   */
  public int lookup(final Object attribute) {
    if (attribute instanceof Integer
        || attribute instanceof Long
        || attribute instanceof Double
        || attribute instanceof Float
        || attribute instanceof Short
        || attribute instanceof Byte) {
      return lookupNumber(((Number) attribute).doubleValue());
    }
    if (attribute instanceof String || attribute instanceof Boolean) {
      return lookupString(String.valueOf(attribute));
    }
    return SCAN;
  }

  private int lookupNumber(final double number) {
    final int index = Double.isNaN(number) ? NONE : this.numeric.get(key(number));
    if (index < this.firstUnconvertible) {
      return MATCH;
    }
    // Without an earlier match, the scan throws at the first element it cannot convert.
    return this.firstUnconvertible == NONE ? NO_MATCH : SCAN;
  }

  private int lookupString(final String attribute) {
    final Integer index = this.strings.get(attribute);
    if (this.firstNumber == NONE) {
      return index != null ? MATCH : NO_MATCH;
    }
    if (index != null && index < this.firstNumber) {
      return MATCH;
    }
    // The scan parses the attribute at the first number element, and throws if it cannot.
    final double number;
    try {
      number = Double.parseDouble(attribute);
    } catch (final NumberFormatException e) {
      return SCAN;
    }
    if (index != null) {
      return MATCH;
    }
    return !Double.isNaN(number) && this.numbers.get(key(number)) != NONE ? MATCH : NO_MATCH;
  }

  /** The key of a number. {@code -0.0 == 0.0} in the scan, so both have the same key. */
  private static long key(final double number) {
    return Double.doubleToLongBits(number == 0 ? 0d : number);
  }

  private static int capacity(final int size) {
    return (int) Math.min(Integer.MAX_VALUE, (long) size * 4 / 3 + 1);
  }

  /**
   * Open addressing with linear probing over primitive long keys, so that a lookup does not box.
   * The table is at most half full.
   */
  static final class LongIntIndex {
    private final long[] keys;
    private final int[] values;
    private final boolean[] used;
    private final int mask;
    private final int shift;

    private LongIntIndex(final int expected) {
      final int capacity = Integer.highestOneBit(Math.max(1, expected) * 2 - 1) << 1;
      this.keys = new long[capacity];
      this.values = new int[capacity];
      this.used = new boolean[capacity];
      this.mask = capacity - 1;
      this.shift = Long.SIZE - Integer.numberOfTrailingZeros(capacity);
    }

    /** Indexes the first {@code count} keys. The first index of a repeated key is kept. */
    static LongIntIndex of(final long[] keys, final int[] values, final int count) {
      final LongIntIndex index = new LongIntIndex(count);
      for (int i = 0; i < count; i++) {
        index.putIfAbsent(keys[i], values[i]);
      }
      return index;
    }

    void putIfAbsent(final long key, final int value) {
      int slot = slot(key);
      while (this.used[slot]) {
        if (this.keys[slot] == key) {
          return;
        }
        slot = (slot + 1) & this.mask;
      }
      this.used[slot] = true;
      this.keys[slot] = key;
      this.values[slot] = value;
    }

    /** Returns the value of the key, or {@link Integer#MAX_VALUE} if the key is absent. */
    int get(final long key) {
      int slot = slot(key);
      while (this.used[slot]) {
        if (this.keys[slot] == key) {
          return this.values[slot];
        }
        slot = (slot + 1) & this.mask;
      }
      return NONE;
    }

    int capacity() {
      return this.keys.length;
    }

    /**
     * Fibonacci hashing: the high bits of the product depend on every bit of the key. The low bits
     * do not: they are zero for small integers and powers of two, whose low mantissa bits are zero.
     */
    int slot(final long key) {
      return (int) ((key * 0x9E3779B97F4A7C15L) >>> this.shift);
    }
  }
}
