package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HashtableSizingTest {

  @Test
  void boundedRejectsNegativeMaxCapacity() {
    assertThrows(IllegalArgumentException.class, () -> Hashtable.Sizing.bounded(-1));
  }

  @Test
  void initialCapacityRejectsNegatives() {
    Hashtable.Sizing sizing = Hashtable.Sizing.bounded(10);
    assertThrows(IllegalArgumentException.class, () -> sizing.initialCapacity(-1));
  }

  @Test
  void defaultIsFixedAtTheFullSize() {
    Hashtable.Sizing large = Hashtable.Sizing.bounded(1000);
    assertFalse(large.hasExplicitInitialCapacity());
    assertEquals(1000, large.maxCapacity());
    assertEquals(0, large.initialCapacity());
    assertEquals(2048, Hashtable.capacityFor(1000));
    assertEquals(2048, Hashtable.initialBucketsFor(large));
    assertEquals(1, Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(0)));
  }

  @Test
  void growableStartsAtAQuarterOfTheFullSize() {
    assertEquals(
        512, Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(1000).initialCapacity(0)));
    // At least 16 buckets, and never more than the full size.
    assertEquals(16, Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(30).initialCapacity(0)));
    assertEquals(
        Hashtable.capacityFor(5),
        Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(5).initialCapacity(0)));
    assertEquals(1, Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(0).initialCapacity(0)));
  }

  @Test
  void explicitInitialCapacityRaisesButNeverLowersTheStart() {
    assertEquals(
        512, Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(1000).initialCapacity(1)));
    assertEquals(
        1024, Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(1000).initialCapacity(700)));
    assertEquals(
        2048, Hashtable.initialBucketsFor(Hashtable.Sizing.bounded(1000).initialCapacity(5000)));
  }

  @Test
  void explicitInitialCapacityIsClampedToTheBound() {
    Hashtable.Sizing sizing = Hashtable.Sizing.bounded(100).initialCapacity(500);
    assertTrue(sizing.hasExplicitInitialCapacity());
    assertEquals(100, sizing.initialCapacity());
    assertEquals(40, Hashtable.Sizing.bounded(100).initialCapacity(40).initialCapacity());
    assertEquals(0, Hashtable.Sizing.bounded(100).initialCapacity(0).initialCapacity());
  }

  @Test
  void initialCapacityReturnsANewInstance() {
    Hashtable.Sizing base = Hashtable.Sizing.bounded(100);
    Hashtable.Sizing withInitial = base.initialCapacity(40);
    assertNotSame(base, withInitial);
    assertFalse(base.hasExplicitInitialCapacity());
    assertEquals(0, base.initialCapacity());
    assertEquals(40, withInitial.initialCapacity());
    assertEquals(100, withInitial.maxCapacity());
  }

  @Test
  void equalsHashCodeAndToString() {
    Hashtable.Sizing a = Hashtable.Sizing.bounded(100).initialCapacity(40);
    Hashtable.Sizing b = Hashtable.Sizing.bounded(100).initialCapacity(40);
    assertEquals(a, a);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, Hashtable.Sizing.bounded(100));
    assertNotEquals(a, Hashtable.Sizing.bounded(101).initialCapacity(40));
    assertNotEquals(a, "not a sizing");
    // An explicit initial capacity equal to the default is still distinguishable from the default.
    assertNotEquals(
        Hashtable.Sizing.bounded(100), Hashtable.Sizing.bounded(100).initialCapacity(12));

    assertEquals("Hashtable.Sizing{maxCapacity=100, initialCapacity=40}", a.toString());
    assertEquals(
        "Hashtable.Sizing{maxCapacity=100, initialCapacity=default}",
        Hashtable.Sizing.bounded(100).toString());
  }
}
