package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TableSizingTest {

  @Test
  void boundedRejectsNegativeMaxCapacity() {
    assertThrows(IllegalArgumentException.class, () -> TableSizing.bounded(-1));
  }

  @Test
  void initialCapacityRejectsNegatives() {
    TableSizing sizing = TableSizing.bounded(10);
    assertThrows(IllegalArgumentException.class, () -> sizing.initialCapacity(-1));
  }

  @Test
  void defaultInitialCapacityIsClampedToTheBound() {
    TableSizing large = TableSizing.bounded(1000);
    assertFalse(large.hasExplicitInitialCapacity());
    assertEquals(1000, large.maxCapacity());
    assertEquals(TableSizing.DEFAULT_INITIAL_CAPACITY, large.initialCapacity());
    assertEquals(16, Hashtable.capacityFor(large.initialCapacity()));

    assertEquals(5, TableSizing.bounded(5).initialCapacity());
    assertEquals(0, TableSizing.bounded(0).initialCapacity());
  }

  @Test
  void explicitInitialCapacityIsClampedToTheBound() {
    TableSizing sizing = TableSizing.bounded(100).initialCapacity(500);
    assertTrue(sizing.hasExplicitInitialCapacity());
    assertEquals(100, sizing.initialCapacity());
    assertEquals(40, TableSizing.bounded(100).initialCapacity(40).initialCapacity());
    assertEquals(0, TableSizing.bounded(100).initialCapacity(0).initialCapacity());
  }

  @Test
  void initialCapacityReturnsANewInstance() {
    TableSizing base = TableSizing.bounded(100);
    TableSizing withInitial = base.initialCapacity(40);
    assertNotSame(base, withInitial);
    assertFalse(base.hasExplicitInitialCapacity());
    assertEquals(TableSizing.DEFAULT_INITIAL_CAPACITY, base.initialCapacity());
    assertEquals(40, withInitial.initialCapacity());
    assertEquals(100, withInitial.maxCapacity());
  }

  @Test
  void equalsHashCodeAndToString() {
    TableSizing a = TableSizing.bounded(100).initialCapacity(40);
    TableSizing b = TableSizing.bounded(100).initialCapacity(40);
    assertEquals(a, a);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, TableSizing.bounded(100));
    assertNotEquals(a, TableSizing.bounded(101).initialCapacity(40));
    assertNotEquals(a, "not a sizing");
    // An explicit initial capacity equal to the default is still distinguishable from the default.
    assertNotEquals(TableSizing.bounded(100), TableSizing.bounded(100).initialCapacity(12));

    assertEquals("TableSizing{maxCapacity=100, initialCapacity=40}", a.toString());
    assertEquals(
        "TableSizing{maxCapacity=100, initialCapacity=default}",
        TableSizing.bounded(100).toString());
  }
}
