package com.datadog.openfeature.internal.ufc;

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
 * <p>Any other attribute is compared as a string, as a string attribute is, unless it can equal a
 * nested element: then {@link #lookup} returns {@link #SCAN}. When the scan throws, {@link #lookup}
 * returns the element it throws at, and the caller compares the attribute with that element only.
 */
public final class MembershipIndex {
  /** Lists shorter than this keep the scan. */
  static final int MIN_INDEXED_SIZE = 2;

  public static final int NO_MATCH = 0;
  public static final int MATCH = 1;

  /** The index cannot decide: run the scan over the list. */
  public static final int SCAN = -1;

  /** Results below this one encode the element where the scan throws: see {@link #throwsAt}. */
  private static final int THROWS = -2;

  private static final int NONE = Integer.MAX_VALUE;

  /**
   * {@code String.valueOf} of each element that is not a number, with its first index when the list
   * has a number element (only then does the index decide a match).
   */
  private final StringTable strings;

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

  /** Whether the list has a nested list or object, which another list or map can equal. */
  private final boolean hasNested;

  private MembershipIndex(final List<?> values) {
    final int size = values.size();
    // The strings of the elements that are not numbers, in list order.
    final String[] stringKeys = new String[size];
    final int[] stringIndexes = new int[size];
    int stringCount = 0;
    // Keys of the elements that convert to a number, then of the number elements, in list order.
    final long[] numericKeys = new long[size];
    final int[] numericIndexes = new int[size];
    int numericCount = 0;
    final long[] numberKeys = new long[size];
    final int[] numberIndexes = new int[size];
    int numberCount = 0;
    int unconvertible = NONE;
    boolean nested = false;
    for (int i = 0; i < size; i++) {
      final Object element = values.get(i);
      nested |= element instanceof List || element instanceof Map;
      if (element instanceof Number) {
        final long key = key(((Number) element).doubleValue());
        numericKeys[numericCount] = key;
        numericIndexes[numericCount++] = i;
        numberKeys[numberCount] = key;
        numberIndexes[numberCount++] = i;
        continue;
      }
      stringKeys[stringCount] = String.valueOf(element);
      stringIndexes[stringCount++] = i;
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
    this.strings = StringTable.of(stringKeys, stringIndexes, stringCount, numberCount > 0);
    this.numeric = LongIntIndex.of(numericKeys, numericIndexes, numericCount);
    // Without numeric strings, both tables hold the same keys and first indexes.
    this.numbers =
        numberCount == numericCount
            ? this.numeric
            : LongIntIndex.of(numberKeys, numberIndexes, numberCount);
    this.firstUnconvertible = unconvertible;
    this.firstNumber = numberCount == 0 ? NONE : numberIndexes[0];
    this.hasNested = nested;
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
   * @return {@link #MATCH}, {@link #NO_MATCH}, {@link #SCAN} if the caller must scan the list, or a
   *     result below {@link #SCAN} if the scan throws: {@link #throwsAt} gives the element.
   */
  public int lookup(final Object attribute) {
    // The scan compares a number attribute through doubleValue, whatever its type.
    if (attribute instanceof Number) {
      return lookupNumber(((Number) attribute).doubleValue());
    }
    // Only a nested element can equal an attribute that is neither a string nor a boolean. Two
    // equal maps can print their keys in a different order, so the string does not decide.
    if (this.hasNested && !(attribute instanceof String) && !(attribute instanceof Boolean)) {
      return SCAN;
    }
    return lookupString(String.valueOf(attribute));
  }

  /** The element where the scan throws, for a {@link #lookup} result below {@link #SCAN}. */
  public static int throwsAt(final int result) {
    return THROWS - result;
  }

  private static int throwing(final int element) {
    return THROWS - element;
  }

  private int lookupNumber(final double number) {
    final int index = Double.isNaN(number) ? NONE : this.numeric.get(key(number));
    if (index < this.firstUnconvertible) {
      return MATCH;
    }
    // Without an earlier match, the scan throws at the first element it cannot convert.
    return this.firstUnconvertible == NONE ? NO_MATCH : throwing(this.firstUnconvertible);
  }

  private int lookupString(final String attribute) {
    final int index = this.strings.get(attribute);
    if (this.firstNumber == NONE) {
      return index != NONE ? MATCH : NO_MATCH;
    }
    if (index < this.firstNumber) {
      return MATCH;
    }
    // The scan parses the attribute at the first number element, and throws if it cannot.
    final double number;
    try {
      number = Double.parseDouble(attribute);
    } catch (final NumberFormatException e) {
      return throwing(this.firstNumber);
    }
    if (index != NONE) {
      return MATCH;
    }
    return !Double.isNaN(number) && this.numbers.get(key(number)) != NONE ? MATCH : NO_MATCH;
  }

  /** The key of a number. {@code -0.0 == 0.0} in the scan, so both have the same key. */
  private static long key(final double number) {
    return Double.doubleToLongBits(number == 0 ? 0d : number);
  }

  /**
   * Open addressing with linear probing over strings, modeled on {@code StringIndex} in {@code
   * internal-api}, which this module cannot depend on: flat arrays instead of a node and a boxed
   * index per string. The cached hashes gate {@code equals}, and a zero hash marks an empty slot.
   * The table is at most half full.
   */
  static final class StringTable {
    /** The table of a list without strings, shared as nothing is ever put in it. */
    private static final StringTable EMPTY = new StringTable(0, false);

    private final String[] keys;
    private final int[] hashes;

    /** The first index of each key, or {@code null} if the table keeps no indexes. */
    @Nullable private final int[] values;

    private StringTable(final int expected, final boolean withValues) {
      final int capacity = Integer.highestOneBit(Math.max(1, expected) * 2 - 1) << 1;
      this.keys = new String[capacity];
      this.hashes = new int[capacity];
      this.values = withValues ? new int[capacity] : null;
    }

    /**
     * Indexes the first {@code count} keys. The first index of a repeated key is kept, and only
     * when {@code withValues} is set.
     */
    static StringTable of(
        final String[] keys, final int[] values, final int count, final boolean withValues) {
      if (count == 0) {
        return EMPTY;
      }
      final StringTable table = new StringTable(count, withValues);
      for (int i = 0; i < count; i++) {
        table.putIfAbsent(keys[i], values[i]);
      }
      return table;
    }

    void putIfAbsent(final String key, final int value) {
      final int hash = hash(key);
      final int mask = this.keys.length - 1;
      int slot = hash & mask;
      while (this.hashes[slot] != 0) {
        if (this.hashes[slot] == hash && this.keys[slot].equals(key)) {
          return;
        }
        slot = (slot + 1) & mask;
      }
      this.hashes[slot] = hash;
      this.keys[slot] = key;
      if (this.values != null) {
        this.values[slot] = value;
      }
    }

    /**
     * Returns the first index of the key, or {@link Integer#MAX_VALUE} if the key is absent. A
     * table without indexes returns 0 for every key it holds.
     */
    int get(final String key) {
      final int hash = hash(key);
      final int mask = this.keys.length - 1;
      int slot = hash & mask;
      int found;
      while ((found = this.hashes[slot]) != 0) {
        if (found == hash && this.keys[slot].equals(key)) {
          return this.values == null ? 0 : this.values[slot];
        }
        slot = (slot + 1) & mask;
      }
      return NONE;
    }

    int capacity() {
      return this.keys.length;
    }

    /** The spread hash of the key; never 0, which marks an empty slot. */
    static int hash(final String key) {
      final int hash = key.hashCode();
      return hash == 0 ? 0xDD06 : hash ^ (hash >>> 16);
    }
  }

  /**
   * Open addressing with linear probing over primitive long keys, so that a lookup does not box.
   * The table is at most half full.
   */
  static final class LongIntIndex {
    /** The table of a list without numbers, shared as nothing is ever put in it. */
    private static final LongIntIndex EMPTY = new LongIntIndex(0);

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
      if (count == 0) {
        return EMPTY;
      }
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
