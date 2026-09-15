package com.datadog.profiling.otel.proto.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StringTableTest {

  private StringTable table;

  @BeforeEach
  void setUp() {
    table = new StringTable();
  }

  @Test
  void indexZeroIsEmptyString() {
    assertEquals("", table.get(0));
    assertEquals(1, table.size());
  }

  @Test
  void internReturnsConsistentIndices() {
    int idx1 = table.intern("foo");
    int idx2 = table.intern("foo");
    assertEquals(idx1, idx2);
  }

  @Test
  void internDifferentStringsReturnsDifferentIndices() {
    int idx1 = table.intern("foo");
    int idx2 = table.intern("bar");
    assertNotEquals(idx1, idx2);
  }

  @Test
  void nullReturnsIndexZero() {
    assertEquals(0, table.intern(null));
  }

  @Test
  void emptyStringReturnsIndexZero() {
    assertEquals(0, table.intern(""));
  }

  @Test
  void resetClearsTable() {
    table.intern("foo");
    table.intern("bar");
    assertEquals(3, table.size());

    table.reset();
    assertEquals(1, table.size());
    assertEquals("", table.get(0));
  }
}
