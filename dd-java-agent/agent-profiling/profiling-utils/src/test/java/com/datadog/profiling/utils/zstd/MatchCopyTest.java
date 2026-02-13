package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.util.UnsafeUtils;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Verifies the ASM-generated MatchCopy produces identical results to System.arraycopy. */
class MatchCopyTest {

  @Test
  void generatedMatchCopyNotNull() {
    MatchCopy mc = AsmEncoders.getMatchCopy();
    assertNotNull(mc, "ASM MatchCopy generation should succeed");
  }

  @Test
  void copyMatchesSystemArrayCopy() {
    MatchCopy mc = AsmEncoders.getMatchCopy();
    assertNotNull(mc);

    Random rng = new Random(42);
    for (int length = 0; length <= 128; length++) {
      byte[] src = new byte[length + 16];
      rng.nextBytes(src);

      byte[] dstExpected = new byte[length + 16];
      byte[] dstActual = new byte[length + 16];

      System.arraycopy(src, 0, dstExpected, 0, length);
      mc.copy(
          dstActual,
          UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
          src,
          UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
          length);

      assertArrayEquals(dstExpected, dstActual, "Mismatch for length=" + length);
    }
  }

  @Test
  void copyWithOffsets() {
    MatchCopy mc = AsmEncoders.getMatchCopy();
    assertNotNull(mc);

    byte[] src = new byte[256];
    new Random(99).nextBytes(src);

    int srcOffset = 17;
    int dstOffset = 31;
    int length = 64;

    byte[] dstExpected = new byte[256];
    byte[] dstActual = new byte[256];

    System.arraycopy(src, srcOffset, dstExpected, dstOffset, length);
    mc.copy(
        dstActual,
        UnsafeUtils.BYTE_ARRAY_BASE_OFFSET + dstOffset,
        src,
        UnsafeUtils.BYTE_ARRAY_BASE_OFFSET + srcOffset,
        length);

    assertArrayEquals(dstExpected, dstActual);
  }

  @Test
  void copyLargeBlock() {
    MatchCopy mc = AsmEncoders.getMatchCopy();
    assertNotNull(mc);

    int length = 8192;
    byte[] src = new byte[length];
    new Random(77).nextBytes(src);

    byte[] dstExpected = new byte[length];
    byte[] dstActual = new byte[length];

    System.arraycopy(src, 0, dstExpected, 0, length);
    mc.copy(
        dstActual,
        UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
        src,
        UnsafeUtils.BYTE_ARRAY_BASE_OFFSET,
        length);

    assertArrayEquals(dstExpected, dstActual);
  }
}
