package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PairTest {

  @Test
  void pumpUpTheCoverage() {
    Pair<String, Integer> one = Pair.of("one", 1);
    Pair<String, Integer> two = Pair.of("two", 2);
    Pair<String, Integer> nothing = Pair.of(null, null);

    assertEquals(one, one);
    assertEquals(Pair.of("one", 1), one);
    assertNotEquals(two, one);
    assertNotEquals(nothing, one);
    assertNotEquals(one, nothing);
    assertEquals(nothing, nothing);
    assertNotEquals(two.getLeft(), one.getLeft());
    assertNotEquals(two.getRight(), one.getRight());
    assertNotEquals(two.hashCode(), one.hashCode());
    assertNotEquals(nothing.hashCode(), one.hashCode());
    assertTrue(one.hasLeft());
    assertTrue(one.hasRight());
    assertFalse(nothing.hasLeft());
    assertFalse(nothing.hasRight());
  }
}
