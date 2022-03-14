package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.Allocators;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LongSequenceTest {
  private LongSequence instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new LongSequence(Allocators.directAllocator(205000 * 8, 256));
  }

  @Test
  void testCapacity() {
    int items = 200000;
    for (int i = 0; i < items; i++) {
      try {
        instance.add(i);
      } catch (Throwable t) {
        System.out.println("===> " + i);
        throw t;
      }
    }
    assertEquals(items, instance.size());
    LongIterator iterator = instance.iterator();
    long value = 0;
    while (iterator.hasNext()) {
      long retrieved = iterator.next();
      assertEquals(value++, retrieved);
    }
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
  void iterator() {}
}
