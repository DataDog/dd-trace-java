package datadog.trace.common.metrics;

/**
 * Sizing for the cardinality handlers' open-addressed tables: powers of two kept at or below a 0.5
 * load factor, from {@link #MIN_CAPACITY} up to the size a handler's full budget needs.
 */
final class CardinalityTables {
  /** The smallest table, holding up to 4 values. */
  static final int MIN_CAPACITY = 8;

  private CardinalityTables() {}

  /** The table size that holds a full budget of {@code cardinalityLimit} values. */
  static int maxCapacity(int cardinalityLimit) {
    return powerOfTwoFor(cardinalityLimit);
  }

  /** The table size that holds {@code values} values, clamped to {@code maxCapacity}. */
  static int capacityFor(int values, int maxCapacity) {
    return Math.min(maxCapacity, Math.max(MIN_CAPACITY, powerOfTwoFor(values)));
  }

  /** The next power of two at or above twice {@code values}. */
  private static int powerOfTwoFor(int values) {
    return Integer.highestOneBit(values * 2 - 1) << 1;
  }
}
