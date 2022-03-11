package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.Allocator;
import java.util.stream.Stream;

import com.datadog.profiling.context.allocator.AllocatedBuffer;
import com.datadog.profiling.context.allocator.Allocators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AllocatorsTest {
  private static final int chunkSize = 32;

  private static final Allocator directAllocator =
      Allocators.directAllocator(chunkSize * 8, chunkSize);;
  private static final Allocator heapAllocator = Allocators.heapAllocator(chunkSize * 8, chunkSize);

  private AllocatedBuffer buffer = null;

  @AfterEach
  void teardown() {
    if (buffer != null) {
      buffer.release();
    }
  }

  @ParameterizedTest
  @MethodSource("allocateBufferTestParams")
  void testAllocatedBuffer(Allocator allocator, int numChunks, int skid) {
    buffer =
        skid == 0
            ? allocator.allocateChunks(numChunks)
            : allocator.allocate(chunkSize * numChunks + skid);
    int iterations = (numChunks * chunkSize) / 8;
    for (int i = 0; i < iterations; i++) {
      assertTrue(buffer.putLong(i));
    }
    assertTrue(numChunks * chunkSize + skid <= buffer.capacity());

    for (int i = 0; i < numChunks; i++) {
      assertTrue(buffer.putLong(i * chunkSize, 5));
      assertEquals(5, buffer.getLong(i * chunkSize));
    }

    if (skid == 0) {
      assertFalse(buffer.putLong(6));
    } else {
      assertTrue(buffer.putLong(6));
    }
    assertFalse(buffer.putLong(buffer.capacity() - 7, 6));

    assertEquals(Long.MIN_VALUE, buffer.getLong(buffer.capacity() - 7));
  }

  private static Stream<Arguments> allocateBufferTestParams() {
    return Stream.of(
        Arguments.of(directAllocator, 1, 0),
        Arguments.of(directAllocator, 2, 0),
        Arguments.of(directAllocator, 1, 1),
        Arguments.of(directAllocator, 2, 1),
        Arguments.of(heapAllocator, 1, 0),
        Arguments.of(heapAllocator, 2, 0),
        Arguments.of(heapAllocator, 1, 1),
        Arguments.of(heapAllocator, 2, 1));
  }
}
