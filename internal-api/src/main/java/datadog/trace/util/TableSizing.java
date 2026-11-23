package datadog.trace.util;

import javax.annotation.Nonnull;

/**
 * Describes how a table sizes its bucket array: the strict cap on live entries, and how many
 * entries the array should fit before its first growth. Shared across table types, so other tables
 * can adopt it later.
 *
 * <p>Immutable: {@link #initialCapacity(int)} returns a new instance, so a spec can be a {@code
 * static final} constant shared by many tables.
 *
 * <pre>{@code
 * static final TableSizing SIZING = TableSizing.bounded(4096).initialCapacity(256);
 * ...
 * Hashtable.D1<String, MyEntry> table = Hashtable.D1.create(MyEntry.class, SIZING);
 * }</pre>
 */
public final class TableSizing {
  /**
   * Initial capacity used when none is given: 12 entries, which {@link Hashtable#capacityFor(int)}
   * maps to a 16-bucket start.
   */
  static final int DEFAULT_INITIAL_CAPACITY = 12;

  /** Marks an initial capacity that was not set explicitly. */
  private static final int UNSET = -1;

  private final int maxCapacity;
  private final int initialCapacity;

  private TableSizing(int maxCapacity, int initialCapacity) {
    this.maxCapacity = maxCapacity;
    this.initialCapacity = initialCapacity;
  }

  /**
   * A spec capped at {@code maxCapacity} live entries, starting at the default initial capacity.
   *
   * @throws IllegalArgumentException if {@code maxCapacity} is negative
   */
  @Nonnull
  public static TableSizing bounded(int maxCapacity) {
    if (maxCapacity < 0) {
      throw new IllegalArgumentException("maxCapacity must be non-negative: " + maxCapacity);
    }
    return new TableSizing(maxCapacity, UNSET);
  }

  /**
   * A copy of this spec whose bucket array starts sized for {@code initialCapacity} live entries,
   * an entry count rather than a bucket count. Values above the max capacity are clamped to it.
   *
   * @throws IllegalArgumentException if {@code initialCapacity} is negative
   */
  @Nonnull
  public TableSizing initialCapacity(int initialCapacity) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException(
          "initialCapacity must be non-negative: " + initialCapacity);
    }
    return new TableSizing(this.maxCapacity, Math.min(initialCapacity, this.maxCapacity));
  }

  /** The strict cap on live entries. */
  int maxCapacity() {
    return this.maxCapacity;
  }

  /** The entry count the initial bucket array fits: explicit, or the default clamped to the cap. */
  int initialCapacity() {
    return hasExplicitInitialCapacity()
        ? this.initialCapacity
        : Math.min(DEFAULT_INITIAL_CAPACITY, this.maxCapacity);
  }

  /** {@code true} when {@link #initialCapacity(int)} set the initial capacity. */
  boolean hasExplicitInitialCapacity() {
    return this.initialCapacity != UNSET;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TableSizing)) {
      return false;
    }
    TableSizing that = (TableSizing) o;
    return this.maxCapacity == that.maxCapacity && this.initialCapacity == that.initialCapacity;
  }

  @Override
  public int hashCode() {
    return 31 * this.maxCapacity + this.initialCapacity;
  }

  @Override
  public String toString() {
    return "TableSizing{maxCapacity="
        + this.maxCapacity
        + ", initialCapacity="
        + (hasExplicitInitialCapacity() ? Integer.toString(this.initialCapacity) : "default")
        + "}";
  }
}
