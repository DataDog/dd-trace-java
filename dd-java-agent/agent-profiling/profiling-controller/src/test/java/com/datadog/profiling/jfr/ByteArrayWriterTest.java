package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ByteArrayWriterTest {
  /** Make sure the packed integer length is correctly calculated */
  @Test
  void testGetPackedIntLen() {
    assertEquals(1, ByteArrayWriter.getPackedIntLen(0));

    long val = 1L;

    for (int i = 1; i < 10; i++) {
      assertEquals(Math.max(i - 1, 1), ByteArrayWriter.getPackedIntLen(val - 1));
      assertEquals(i, ByteArrayWriter.getPackedIntLen(val));
      val = val << 7;
    }
  }
}
