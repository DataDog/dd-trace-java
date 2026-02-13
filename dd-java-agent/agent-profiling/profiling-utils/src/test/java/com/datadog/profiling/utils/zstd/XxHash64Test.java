package com.datadog.profiling.utils.zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.util.UnsafeUtils;
import org.junit.jupiter.api.Test;

/** Verify xxHash64 against known test vectors from the xxHash specification. */
class XxHash64Test {

  // Official test vectors from https://github.com/Cyan4973/xxHash (seed=0)
  @Test
  void emptyInput() {
    long hash = XxHash64.hash(0, new byte[0], UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 0);
    assertEquals(0xEF46DB3751D8E999L, hash);
  }

  @Test
  void singleByte() {
    byte[] data = {0};
    long hash = XxHash64.hash(0, data, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, 1);
    assertEquals(0xE934A84ADB052768L, hash);
  }

  @Test
  void fourteenBytes() {
    byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
    long hash = XxHash64.hash(0, data, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, data.length);
    assertEquals(0x5CDA8B69BBFC1D45L, hash);
  }

  @Test
  void streamingMatchesOneShot() {
    byte[] data = new byte[256];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) i;
    }

    long oneShot = XxHash64.hash(0, data, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, data.length);

    // streaming: feed in chunks
    XxHash64 streaming = new XxHash64();
    streaming.update(data, 0, 100);
    streaming.update(data, 100, 56);
    streaming.update(data, 156, 100);
    long streamingHash = streaming.hash();

    assertEquals(oneShot, streamingHash);
  }

  @Test
  void streamingSmallChunks() {
    byte[] data = new byte[128];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) (i * 7);
    }

    long oneShot = XxHash64.hash(0, data, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET, data.length);

    // feed one byte at a time
    XxHash64 streaming = new XxHash64();
    for (int i = 0; i < data.length; i++) {
      streaming.update(data, i, 1);
    }

    assertEquals(oneShot, streaming.hash());
  }

  @Test
  void longValueHash() {
    // hash(long) should be consistent
    long h1 = XxHash64.hash(0x0102030405060708L);
    long h2 = XxHash64.hash(0x0102030405060708L);
    assertEquals(h1, h2);

    // and different from another value
    long h3 = XxHash64.hash(0x0807060504030201L);
    assert h1 != h3;
  }
}
