package com.datadog.profiling.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LongSequenceTest {
  private LongSequence instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new LongSequence();
  }

  @Test
  void test() {
    for (int i = 0; i < 100000; i++) {
      try {
        instance.add(i);
      } catch (Throwable t) {
        System.out.println("===> " + i);
        throw t;
      }
    }
    LongIterator iterator = instance.iterator();
    long value = 0;
    while (iterator.hasNext()) {
      long retrieved = iterator.next();
      assertEquals(value++, retrieved);
    }
  }

  @Test
  void iterator() {
  }
}
