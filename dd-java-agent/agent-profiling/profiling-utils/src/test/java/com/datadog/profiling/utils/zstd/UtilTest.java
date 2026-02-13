package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

class UtilTest {

  @Test
  void highestBit() {
    assertEquals(0, Util.highestBit(1));
    assertEquals(1, Util.highestBit(2));
    assertEquals(1, Util.highestBit(3));
    assertEquals(7, Util.highestBit(128));
    assertEquals(7, Util.highestBit(255));
    assertEquals(10, Util.highestBit(1024));
  }

  @Test
  void isPowerOf2() {
    assertTrue(Util.isPowerOf2(1));
    assertTrue(Util.isPowerOf2(2));
    assertTrue(Util.isPowerOf2(4));
    assertTrue(Util.isPowerOf2(1024));
    assertFalse(Util.isPowerOf2(3));
    assertFalse(Util.isPowerOf2(6));
    assertFalse(Util.isPowerOf2(1000));
  }

  @Test
  void mask() {
    assertEquals(0, Util.mask(0));
    assertEquals(1, Util.mask(1));
    assertEquals(3, Util.mask(2));
    assertEquals(7, Util.mask(3));
    assertEquals(0xFF, Util.mask(8));
    assertEquals(0xFFFF, Util.mask(16));
  }

  @Test
  void checkArgumentPasses() {
    Util.checkArgument(true, "should not throw");
  }

  @Test
  void checkArgumentFails() {
    assertThrows(IllegalArgumentException.class, () -> Util.checkArgument(false, "expected"));
  }

  @Test
  void checkStatePasses() {
    Util.checkState(true, "should not throw");
  }

  @Test
  void checkStateFails() {
    assertThrows(IllegalStateException.class, () -> Util.checkState(false, "expected"));
  }

  @Test
  void checkPositionIndexesValid() {
    Util.checkPositionIndexes(0, 0, 0);
    Util.checkPositionIndexes(0, 5, 10);
    Util.checkPositionIndexes(3, 7, 10);
    Util.checkPositionIndexes(0, 10, 10);
  }

  @Test
  void checkPositionIndexesStartNegative() {
    assertThrows(IndexOutOfBoundsException.class, () -> Util.checkPositionIndexes(-1, 5, 10));
  }

  @Test
  void checkPositionIndexesEndBeforeStart() {
    assertThrows(IndexOutOfBoundsException.class, () -> Util.checkPositionIndexes(5, 3, 10));
  }

  @Test
  void checkPositionIndexesEndExceedsSize() {
    assertThrows(IndexOutOfBoundsException.class, () -> Util.checkPositionIndexes(0, 11, 10));
  }

  @Test
  void get24BitAndPut24BitLittleEndianRoundTrip() {
    byte[] buffer = new byte[8];
    long baseOffset = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;

    int value = 0xABCDEF;
    Util.put24BitLittleEndian(buffer, baseOffset, value);
    int readBack = Util.get24BitLittleEndian(buffer, baseOffset);
    assertEquals(value, readBack);

    // Test with a smaller value
    int small = 0x123;
    Util.put24BitLittleEndian(buffer, baseOffset + 3, small);
    assertEquals(small, Util.get24BitLittleEndian(buffer, baseOffset + 3));
  }

  @Test
  void minTableLog() {
    // 256 symbols, 1024 input → should be reasonable
    int result = Util.minTableLog(1024, 255);
    assertTrue(result > 0);
    assertTrue(result <= 12);
  }

  @Test
  void minTableLogSmallInput() {
    assertThrows(IllegalArgumentException.class, () -> Util.minTableLog(1, 10));
  }

  @Test
  void cycleLogNonBtStrategy() {
    // Non-BT strategies → cycleLog equals hashLog
    assertEquals(17, Util.cycleLog(17, CompressionParameters.Strategy.DFAST));
    assertEquals(17, Util.cycleLog(17, CompressionParameters.Strategy.FAST));
    assertEquals(17, Util.cycleLog(17, CompressionParameters.Strategy.GREEDY));
    assertEquals(17, Util.cycleLog(17, CompressionParameters.Strategy.LAZY));
    assertEquals(17, Util.cycleLog(17, CompressionParameters.Strategy.LAZY2));
  }

  @Test
  void cycleLogBtStrategies() {
    // BT strategies → cycleLog = hashLog - 1
    assertEquals(16, Util.cycleLog(17, CompressionParameters.Strategy.BTLAZY2));
    assertEquals(16, Util.cycleLog(17, CompressionParameters.Strategy.BTOPT));
    assertEquals(16, Util.cycleLog(17, CompressionParameters.Strategy.BTULTRA));
  }
}
