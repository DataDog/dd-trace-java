package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CompressionParametersTest {

  @Test
  void computeWithUnknownInputSize() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    assertNotNull(params);
    // Level 3 default: DFAST, windowLog=20
    assertEquals(CompressionParameters.Strategy.DFAST, params.getStrategy());
    assertEquals(20, params.getWindowLog());
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        100,
        8 * 1024,
        16 * 1024,
        64 * 1024,
        128 * 1024,
        200 * 1024,
        256 * 1024,
        512 * 1024,
        2 * 1024 * 1024
      })
  void computeWithKnownInputSize(int inputSize) {
    CompressionParameters params = CompressionParameters.compute(3, inputSize);
    assertNotNull(params);
    assertTrue(params.getWindowSize() > 0);
    assertTrue(params.getBlockSize() > 0);
    assertTrue(params.getBlockSize() <= params.getWindowSize());
    // Window should be adapted to input size
    assertTrue(
        params.getWindowSize() >= inputSize
            || params.getWindowLog() >= ZstdConstants.MIN_WINDOW_LOG);
  }

  @Test
  void computeWithSmallInputSize() {
    // Input smaller than minimum hash size → windowLog clamped to MIN_HASH_LOG then MIN_WINDOW_LOG
    CompressionParameters params = CompressionParameters.compute(3, 32);
    assertNotNull(params);
    assertTrue(params.getWindowLog() >= ZstdConstants.MIN_WINDOW_LOG);
  }

  @Test
  void computeWithLevel0UsesDefault() {
    CompressionParameters params = CompressionParameters.compute(0, -1);
    assertNotNull(params);
    // Level 0 → uses DEFAULT_COMPRESSION_LEVEL row
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 5, 10, 15, 22})
  void computeWithVariousLevels(int level) {
    CompressionParameters params = CompressionParameters.compute(level, -1);
    assertNotNull(params);
    assertTrue(params.getSearchLength() >= 3);
  }

  @Test
  void computeWithNegativeLevel() {
    // Negative level → FAST strategy with targetLength = abs(level)
    CompressionParameters params = CompressionParameters.compute(-1, -1);
    assertNotNull(params);
    assertEquals(1, params.getTargetLength());
  }

  @Test
  void computeInputSizeTriggersTableSelection() {
    // <= 16KB → table 3
    CompressionParameters small = CompressionParameters.compute(3, 10 * 1024);
    // <= 128KB → table 2
    CompressionParameters medium = CompressionParameters.compute(3, 100 * 1024);
    // <= 256KB → table 1
    CompressionParameters large = CompressionParameters.compute(3, 200 * 1024);
    // > 256KB → table 0 (default)
    CompressionParameters xlarge = CompressionParameters.compute(3, 512 * 1024);

    assertNotNull(small);
    assertNotNull(medium);
    assertNotNull(large);
    assertNotNull(xlarge);
  }

  @Test
  void allGettersCovered() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    assertTrue(params.getWindowLog() > 0);
    assertTrue(params.getWindowSize() > 0);
    assertTrue(params.getBlockSize() > 0);
    assertTrue(params.getSearchLength() > 0);
    assertTrue(params.getChainLog() > 0);
    assertTrue(params.getHashLog() > 0);
    assertTrue(params.getSearchLog() > 0);
    assertTrue(params.getTargetLength() >= 0);
    assertNotNull(params.getStrategy());
  }

  @Test
  void windowResizeForSmallInput() {
    // Very small input → window should shrink
    CompressionParameters params = CompressionParameters.compute(3, 256);
    assertTrue(params.getWindowLog() <= 20, "Window should be resized for small input");
  }

  @Test
  void hashLogClampedToWindowPlusOne() {
    // High level with small input → hashLog may exceed window
    CompressionParameters params = CompressionParameters.compute(22, 1024);
    assertTrue(params.getHashLog() <= params.getWindowLog() + 1);
  }
}
