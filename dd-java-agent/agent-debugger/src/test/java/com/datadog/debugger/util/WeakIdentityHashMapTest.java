package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class WeakIdentityHashMapTest {

  @Test
  public void referenceEq() {
    WeakIdentityHashMap<Object, Object> map = new WeakIdentityHashMap<>();
    Object key = new BadClass();
    Object value = new Object();
    map.put(key, value);
    assertTrue(map.containsKey(key));
    assertEquals(value, map.get(key));
  }

  @Test
  public void weakKey() {
    WeakIdentityHashMap<Object, Object> map = new WeakIdentityHashMap<>();
    map.put(new BadClass(), new Object());
    System.gc(); // clear weak reference
    int count = 0;
    // size method will trigger a check on the reference queue and eventually purge the stale entry
    while (map.size() > 0 && count < 1000) {
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
      count++;
    }
    assertEquals(0, map.size());
  }

  static class BadClass extends Object {
    @Override
    public int hashCode() {
      return 1;
    }

    @Override
    public boolean equals(Object obj) {
      return false;
    }
  }
}
