package com.datadog.profiling.otel.dictionary;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkTableTest {

  private LinkTable table;

  @BeforeEach
  void setUp() {
    table = new LinkTable();
  }

  @Test
  void indexZeroIsEmptyLink() {
    LinkTable.LinkEntry entry = table.get(0);
    assertEquals(16, entry.traceId.length);
    assertEquals(8, entry.spanId.length);
    // All zeros
    for (byte b : entry.traceId) {
      assertEquals(0, b);
    }
    for (byte b : entry.spanId) {
      assertEquals(0, b);
    }
    assertEquals(1, table.size());
  }

  @Test
  void internNullReturnsIndexZero() {
    assertEquals(0, table.intern(null, null));
    assertEquals(0, table.intern(new byte[16], null));
    assertEquals(0, table.intern(null, new byte[8]));
  }

  @Test
  void internAllZerosReturnsIndexZero() {
    assertEquals(0, table.intern(new byte[16], new byte[8]));
  }

  @Test
  void internLongZerosReturnsIndexZero() {
    assertEquals(0, table.intern(0L, 0L));
  }

  @Test
  void internReturnsConsistentIndices() {
    byte[] traceId = new byte[16];
    traceId[0] = 1;
    byte[] spanId = new byte[8];
    spanId[0] = 2;

    int idx1 = table.intern(traceId, spanId);

    byte[] traceId2 = new byte[16];
    traceId2[0] = 1;
    byte[] spanId2 = new byte[8];
    spanId2[0] = 2;

    int idx2 = table.intern(traceId2, spanId2);
    assertEquals(idx1, idx2);
  }

  @Test
  void internDifferentLinksReturnsDifferentIndices() {
    byte[] traceId1 = new byte[16];
    traceId1[0] = 1;
    byte[] spanId1 = new byte[8];
    spanId1[0] = 1;

    byte[] traceId2 = new byte[16];
    traceId2[0] = 2;
    byte[] spanId2 = new byte[8];
    spanId2[0] = 2;

    int idx1 = table.intern(traceId1, spanId1);
    int idx2 = table.intern(traceId2, spanId2);
    assertNotEquals(idx1, idx2);
  }

  @Test
  void internFromLongValues() {
    int idx = table.intern(0x123456789ABCDEF0L, 0xFEDCBA9876543210L);
    assertNotEquals(0, idx);

    LinkTable.LinkEntry entry = table.get(idx);
    // Trace ID should have value in lower 64 bits (big-endian)
    assertEquals(0x12, entry.traceId[8] & 0xFF);
    assertEquals(0x34, entry.traceId[9] & 0xFF);
    // Span ID should be big-endian
    assertEquals((byte) 0xFE, entry.spanId[0]);
    assertEquals((byte) 0xDC, entry.spanId[1]);
  }

  @Test
  void internMakesDefensiveCopy() {
    byte[] traceId = new byte[16];
    traceId[0] = 1;
    byte[] spanId = new byte[8];
    spanId[0] = 2;

    int idx = table.intern(traceId, spanId);
    traceId[0] = 99; // modify original
    spanId[0] = 99;

    LinkTable.LinkEntry entry = table.get(idx);
    assertEquals(1, entry.traceId[0]); // should be unchanged
    assertEquals(2, entry.spanId[0]);
  }

  @Test
  void sizeIncrementsCorrectly() {
    assertEquals(1, table.size()); // empty link at 0
    table.intern(1L, 1L);
    assertEquals(2, table.size());
    table.intern(2L, 2L);
    assertEquals(3, table.size());
    table.intern(1L, 1L); // duplicate
    assertEquals(3, table.size());
  }

  @Test
  void resetClearsTable() {
    table.intern(1L, 1L);
    table.intern(2L, 2L);
    assertEquals(3, table.size());

    table.reset();
    assertEquals(1, table.size());
  }

  @Test
  void getLinksReturnsAllLinks() {
    table.intern(1L, 1L);
    table.intern(2L, 2L);

    assertEquals(3, table.getLinks().size());
  }
}
