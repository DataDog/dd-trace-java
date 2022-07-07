package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.AllocatedBuffer;
import com.datadog.profiling.context.allocator.Allocators;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AllocatorsTest {
  private static final int maxChunks = 16;
  private static final int chunkSize = 32;

  private static final Allocator directAllocator =
      Allocators.directAllocator(chunkSize * maxChunks, chunkSize);
  private static final Allocator heapAllocator =
      Allocators.heapAllocator(chunkSize * maxChunks, chunkSize);

  private AllocatedBuffer buffer = null;

  @AfterEach
  void teardown() {
    if (buffer != null) {
      buffer.release();
    }
  }

  @ParameterizedTest
  @MethodSource("allocators")
  void testAllocations(Allocator allocator) {
    AllocatedBuffer buffer1 = null;
    AllocatedBuffer buffer2 = null;
    AllocatedBuffer buffer3 = null;
    try {
      buffer1 = allocator.allocateChunks(maxChunks - 2);
      buffer2 = allocator.allocate(chunkSize);
      buffer3 = allocator.allocate(chunkSize / 2);
      buffer = allocator.allocate(chunkSize / 4);

      assertNotNull(buffer1);
      assertNotNull(buffer2);
      assertNotNull(buffer3);
      assertNull(buffer);

      assertEquals(chunkSize * (maxChunks - 2), buffer1.capacity());
      assertEquals(chunkSize, buffer2.capacity());
      assertEquals(chunkSize, buffer3.capacity());
    } finally {
      if (buffer1 != null) {
        buffer1.release();
      }
      if (buffer2 != null) {
        buffer2.release();
      }
      if (buffer3 != null) {
        buffer3.release();
      }
    }
  }

  private static Stream<Arguments> allocators() {
    return Stream.of(Arguments.of(heapAllocator), Arguments.of(directAllocator));
  }

  @Test
  void testOverallocationDirect() {
    buffer = directAllocator.allocateChunks(maxChunks);
    assertNotNull(buffer);
    assertNull(directAllocator.allocate(10));
  }

  @Test
  void testOverallocationHeap() {
    buffer = heapAllocator.allocateChunks(maxChunks);
    assertNotNull(buffer);
    assertNull(heapAllocator.allocate(10));
  }

  @Test
  void testDirectBufferIterator() {
    buffer = directAllocator.allocate(512);

    LongIterator iterator = buffer.iterator();
    assertFalse(iterator.hasNext());
    assertFalse(iterator.hasNext());

    buffer.putLong(0L);
    iterator = buffer.iterator();
    assertTrue(iterator.hasNext());
    assertTrue(iterator.hasNext());

    iterator.next();
    assertFalse(iterator.hasNext());

    // fill in data spanning multiple chunks
    // there is already 1 value stored so we need to add only chunkSize other values
    for (int i = 0; i < chunkSize; i++) {
      buffer.putLong(i + 1);
    }
    iterator = buffer.iterator();
    int cnt = 0;
    while (iterator.hasNext()) {
      assertNotNull(iterator.next());
      cnt++;
    }
    assertEquals(chunkSize + 1, cnt);
  }

  @Test
  void testHeapBufferIterator() {
    buffer = heapAllocator.allocate(256);

    LongIterator iterator = buffer.iterator();
    assertFalse(iterator.hasNext());
    assertFalse(iterator.hasNext());

    buffer.putLong(0L);
    iterator = buffer.iterator();
    assertTrue(iterator.hasNext());
    assertTrue(iterator.hasNext());

    iterator.next();
    assertFalse(iterator.hasNext());
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
