package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

/** Tests for the bit-level output stream used by entropy encoders. */
class BitOutputStreamTest {

  @Test
  void singleBit() {
    byte[] output = new byte[16];
    BitOutputStream bos =
        new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, output.length);
    bos.addBits(1, 1);
    int size = bos.close();
    assertTrue(size > 0, "Stream should produce output");
    // close adds end mark (1 bit), so we have: value=1 (1 bit) + end mark=1 (1 bit)
    // = 0b11 in first byte
    assertEquals(0b11, output[0] & 0xFF);
  }

  @Test
  void eightBitsValue() {
    byte[] output = new byte[16];
    BitOutputStream bos =
        new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, output.length);
    bos.addBits(0xAB, 8);
    int size = bos.close();
    assertTrue(size > 0);
    // first byte is the lower 8 bits of value
    assertEquals(0xAB, output[0] & 0xFF);
    // second byte starts with the end mark bit
    assertEquals(0x01, output[1] & 0xFF);
  }

  @Test
  void multipleBitsAccumulate() {
    byte[] output = new byte[16];
    BitOutputStream bos =
        new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, output.length);
    bos.addBits(0b101, 3); // 3 bits: 101
    bos.addBits(0b11, 2); // 2 bits: 11
    // accumulated: 11_101 = 0x1D (5 bits)
    int size = bos.close();
    assertTrue(size > 0);
    // 5 bits of data + 1 end mark bit = 6 bits = 0b1_11101 = 0x3D
    assertEquals(0x3D, output[0] & 0xFF);
  }

  @Test
  void flushWritesFullBytes() {
    byte[] output = new byte[16];
    BitOutputStream bos =
        new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, output.length);
    bos.addBits(0xFF, 8);
    bos.addBits(0xAA, 8);
    bos.flush();
    // after flush, first 2 bytes should be written
    assertEquals(0xFF, output[0] & 0xFF);
    assertEquals(0xAA, output[1] & 0xFF);
  }

  @Test
  void addBitsFastMatchesAddBits() {
    byte[] output1 = new byte[16];
    byte[] output2 = new byte[16];

    BitOutputStream bos1 =
        new BitOutputStream(output1, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, output1.length);
    bos1.addBits(42, 8);
    bos1.addBits(7, 3);
    int size1 = bos1.close();

    BitOutputStream bos2 =
        new BitOutputStream(output2, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, output2.length);
    bos2.addBitsFast(42, 8);
    bos2.addBitsFast(7, 3);
    int size2 = bos2.close();

    assertEquals(size1, size2);
    for (int i = 0; i < size1; i++) {
      assertEquals(output1[i], output2[i], "Mismatch at byte " + i);
    }
  }

  @Test
  void closeReturnsZeroOnOverflow() {
    byte[] output = new byte[8]; // minimum size
    BitOutputStream bos =
        new BitOutputStream(output, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, output.length);
    // fill up the stream
    for (int i = 0; i < 100; i++) {
      bos.addBits(0xFF, 8);
      bos.flush();
    }
    int size = bos.close();
    assertEquals(0, size, "Should return 0 on overflow");
  }
}
