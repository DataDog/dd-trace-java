package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class CardinalityTablesTest {

  /** A full budget fits at or below a 0.5 load factor, in a power-of-two table. */
  @TableTest({
    "scenario         | cardinalityLimit | maxCapacity",
    "one value        | 1                | 2          ",
    "exact half       | 4                | 8          ",
    "just over half   | 5                | 16         ",
    "peer tag default | 512              | 1024       ",
    "largest allowed  | 536870912        | 1073741824 "
  })
  void maxCapacityHoldsAFullBudget(int cardinalityLimit, int maxCapacity) {
    assertEquals(maxCapacity, CardinalityTables.maxCapacity(cardinalityLimit));
  }

  /**
   * A table grows to fit its values, never below the 8-slot minimum unless the budget's own table
   * is smaller, and never above that table.
   */
  @TableTest({
    "scenario                 | values | maxCapacity | capacity",
    "no values yet            | 0      | 1024        | 8       ",
    "a few values             | 3      | 1024        | 8       ",
    "past the minimum         | 5      | 1024        | 16      ",
    "clamped to the budget    | 600    | 1024        | 1024    ",
    "budget below the minimum | 1      | 2           | 2       "
  })
  void capacityForFitsTheValuesWithinTheBudget(int values, int maxCapacity, int capacity) {
    assertEquals(capacity, CardinalityTables.capacityFor(values, maxCapacity));
  }
}
