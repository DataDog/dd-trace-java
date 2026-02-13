package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

class CompressionContextTest {

  @Test
  void constructorWithSearchLength3() {
    // Level 1 FAST has searchLength=6, level 5 GREEDY has searchLength=5
    // Level 3 DFAST has searchLength=5. Need searchLength==3 → level 19 BTOPT has searchLength=3
    CompressionParameters params = CompressionParameters.compute(19, -1);
    CompressionContext ctx =
        new CompressionContext(params, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, Integer.MAX_VALUE);
    assertNotNull(ctx.sequenceStore);
    assertNotNull(ctx.blockCompressionState);
  }

  @Test
  void constructorWithSearchLength5() {
    // Level 3 DFAST has searchLength=5 → divider = 4
    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext ctx =
        new CompressionContext(params, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, Integer.MAX_VALUE);
    assertNotNull(ctx.sequenceStore);
  }

  @Test
  void constructorWithSmallInputSize() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    // windowSize will be clamped to min(windowSize, inputSize) = min(1MB, 100) = 100
    CompressionContext ctx =
        new CompressionContext(params, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 100);
    assertNotNull(ctx.sequenceStore);
  }

  @Test
  void slideWindowPositiveSize() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext ctx =
        new CompressionContext(params, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, Integer.MAX_VALUE);
    ctx.slideWindow(1024);
  }

  @Test
  void slideWindowZeroThrows() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext ctx =
        new CompressionContext(params, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> ctx.slideWindow(0));
  }

  @Test
  void slideWindowNegativeThrows() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext ctx =
        new CompressionContext(params, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> ctx.slideWindow(-1));
  }

  @Test
  void commit() {
    CompressionParameters params = CompressionParameters.compute(3, -1);
    CompressionContext ctx =
        new CompressionContext(params, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, Integer.MAX_VALUE);
    // Should not throw
    ctx.commit();
  }
}
