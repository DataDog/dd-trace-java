package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IMLTChunkTest {
  @Test
  void compressionNoRepetition() {
    assertArrayEquals(
        new int[] {1, 2, 3, 4}, IMLTChunk.compressStackPtrs(IntStream.of(1, 2, 3, 4)).toArray());
  }

  @Test
  void compressionRepetition() {
    assertArrayEquals(
        new int[] {1, 3 | MLTConstants.EVENT_REPEAT_FLAG, 2},
        IMLTChunk.compressStackPtrs(IntStream.of(1, 1, 1, 1, 2)).toArray());
  }

  @Test
  void compressionRepetitionShort() {
    assertArrayEquals(
        new int[] {
          43, 15, 1 | MLTConstants.EVENT_REPEAT_FLAG, 45, 1 | MLTConstants.EVENT_REPEAT_FLAG
        },
        IMLTChunk.compressStackPtrs(IntStream.of(43, 15, 15, 45, 45)).toArray());
  }

  @Test
  void decompressionNoRepetition() {
    int[] data = new int[] {1, 2, 3, 4};
    assertArrayEquals(data, IMLTChunk.decompressStackPtrs(IntStream.of(data)).toArray());
  }

  @Test
  void decompressionRepetition() {
    int[] data = new int[] {1, 3 | MLTConstants.EVENT_REPEAT_FLAG, 2};
    int[] expected = new int[] {1, 1, 1, 1, 2};
    assertArrayEquals(expected, IMLTChunk.decompressStackPtrs(IntStream.of(data)).toArray());
  }
}
