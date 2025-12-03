package com.datadog.profiling.otel.proto.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FunctionTableTest {

  private FunctionTable table;

  @BeforeEach
  void setUp() {
    table = new FunctionTable();
  }

  @Test
  void indexZeroIsNullFunction() {
    FunctionTable.FunctionEntry entry = table.get(0);
    assertEquals(0, entry.nameIndex);
    assertEquals(0, entry.systemNameIndex);
    assertEquals(0, entry.filenameIndex);
    assertEquals(0, entry.startLine);
    assertEquals(1, table.size());
  }

  @Test
  void internAllZerosReturnsIndexZero() {
    assertEquals(0, table.intern(0, 0, 0, 0));
  }

  @Test
  void internReturnsConsistentIndices() {
    int idx1 = table.intern(1, 2, 3, 10);
    int idx2 = table.intern(1, 2, 3, 10);
    assertEquals(idx1, idx2);
  }

  @Test
  void internDifferentFunctionsReturnsDifferentIndices() {
    int idx1 = table.intern(1, 2, 3, 10);
    int idx2 = table.intern(1, 2, 3, 20); // different start line
    assertNotEquals(idx1, idx2);
  }

  @Test
  void resetClearsTable() {
    table.intern(1, 2, 3, 10);
    table.intern(4, 5, 6, 20);
    assertEquals(3, table.size());

    table.reset();
    assertEquals(1, table.size());
  }
}
