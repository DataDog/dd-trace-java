package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.Allocators;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LongSequenceTest {
  private LongSequence instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new LongSequence(Allocators.heapAllocator(512, 32));
  }

  @Test
  void testCapacity() {
    int items = 64;
    for (int i = 0; i < items; i++) {
      try {
        assertTrue(instance.add(i) > 0);
      } catch (Throwable t) {
        System.out.println("===> " + i);
        throw t;
      }
    }
    assertFalse(instance.add(65) > 0);
    assertEquals(items, instance.size());
    LongIterator iterator = instance.iterator();
    long value = 0;
    while (iterator.hasNext()) {
      long retrieved = iterator.next();
      assertEquals(value++, retrieved);
    }
  }

  @Test
  void testDoubleRelease() {
    instance.release();
    instance.release();
  }

  @Test
  void testPositionalSetGet() {
    for (int i = 1; i <= 3; i++) {
      instance.add(i);
    }

    for (int i = 1; i <= 3; i++) {
      assertEquals(i, instance.get(i - 1));
      instance.set(i - 1, 2 * i);
      assertEquals(2 * i, instance.get(i - 1));
    }
  }

  @Test
  void testGetInvalidIndex() {
    assertEquals(Long.MIN_VALUE, instance.get(5));
    assertEquals(Long.MIN_VALUE, instance.get(65));
  }

  @Test
  void testSetInvalidIndex() {
    assertFalse(instance.set(65, 0L));
  }

  @Test
  void testIteratorInvalid() {
    LongIterator iterator = instance.iterator();
    assertThrows(IllegalStateException.class, iterator::next);
  }

  @Test
  void testIteratorEmpty() {
    LongIterator iterator = instance.iterator();
    assertFalse(iterator.hasNext());
  }

  @Test
  void testIterator() {
    // fill in data
    for (int i = 0; i < 64; i++) {
      instance.add(i);
    }

    long expected = 0;
    LongIterator iterator = instance.iterator();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      assertEquals(expected++, iterator.next());
    }
    assertEquals(expected, instance.size());
    assertFalse(iterator.hasNext());
  }
}
