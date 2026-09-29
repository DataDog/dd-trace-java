package com.datadog.profiling.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LongKeyIntMapTest {

  @Test
  void singleKeyPutGet() {
    LongKeyIntMap map = new LongKeyIntMap(1, 16);
    assertEquals(-1, map.get1(42L));

    map.put1(42L, 7);
    assertEquals(7, map.get1(42L));
    assertEquals(-1, map.get1(43L));

    // replacement
    map.put1(42L, 9);
    assertEquals(9, map.get1(42L));
  }

  @Test
  void singleKeyAnyLongValueIsLegal() {
    LongKeyIntMap map = new LongKeyIntMap(1, 16);
    map.put1(0L, 1);
    map.put1(-1L, 2);
    map.put1(Long.MIN_VALUE, 3);
    map.put1(Long.MAX_VALUE, 4);

    assertEquals(1, map.get1(0L));
    assertEquals(2, map.get1(-1L));
    assertEquals(3, map.get1(Long.MIN_VALUE));
    assertEquals(4, map.get1(Long.MAX_VALUE));
  }

  @Test
  void tripleKeyPutGet() {
    LongKeyIntMap map = new LongKeyIntMap(3, 16);
    assertEquals(-1, map.get3(1L, 2L, 3L));

    map.put3(1L, 2L, 3L, 10);
    assertEquals(10, map.get3(1L, 2L, 3L));
    assertEquals(-1, map.get3(1L, 2L, 4L));
    assertEquals(-1, map.get3(0L, 2L, 3L));

    map.put3(1L, 2L, 3L, 11);
    assertEquals(11, map.get3(1L, 2L, 3L));
  }

  @Test
  void growsBeyondInitialCapacity() {
    LongKeyIntMap map = new LongKeyIntMap(1, 16);
    int count = 10_000;
    for (long i = 0; i < count; i++) {
      map.put1(i, (int) (i * 3));
    }
    assertEquals(count, map.size());
    for (long i = 0; i < count; i++) {
      assertEquals((int) (i * 3), map.get1(i));
    }
  }

  @Test
  void clearResetsContents() {
    LongKeyIntMap map = new LongKeyIntMap(3, 16);
    map.put3(1L, 2L, 3L, 5);
    map.clear();
    assertEquals(0, map.size());
    assertEquals(-1, map.get3(1L, 2L, 3L));
  }
}
