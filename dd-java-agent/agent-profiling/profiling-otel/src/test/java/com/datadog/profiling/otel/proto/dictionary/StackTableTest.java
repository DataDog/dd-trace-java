package com.datadog.profiling.otel.proto.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StackTableTest {

  private StackTable table;

  @BeforeEach
  void setUp() {
    table = new StackTable();
  }

  @Test
  void indexZeroIsEmptyStack() {
    StackTable.StackEntry entry = table.get(0);
    assertEquals(0, entry.locationIndices.length);
    assertEquals(1, table.size());
  }

  @Test
  void internNullReturnsIndexZero() {
    assertEquals(0, table.intern(null));
  }

  @Test
  void internEmptyArrayReturnsIndexZero() {
    assertEquals(0, table.intern(new int[0]));
  }

  @Test
  void internReturnsConsistentIndices() {
    int[] locations = {1, 2, 3};
    int idx1 = table.intern(locations);
    int idx2 = table.intern(new int[] {1, 2, 3});
    assertEquals(idx1, idx2);
  }

  @Test
  void internDifferentStacksReturnsDifferentIndices() {
    int idx1 = table.intern(new int[] {1, 2, 3});
    int idx2 = table.intern(new int[] {1, 2, 4});
    assertNotEquals(idx1, idx2);
  }

  @Test
  void internMakesDefensiveCopy() {
    int[] locations = {1, 2, 3};
    int idx = table.intern(locations);
    locations[0] = 999; // modify original
    StackTable.StackEntry entry = table.get(idx);
    assertEquals(1, entry.locationIndices[0]); // should be unchanged
  }

  @Test
  void resetClearsTable() {
    table.intern(new int[] {1, 2, 3});
    table.intern(new int[] {4, 5, 6});
    assertEquals(3, table.size());

    table.reset();
    assertEquals(1, table.size());
  }
}
