package com.datadog.profiling.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreadMapTest {
  @Test
  void sanity() {
    ThreadMap<String> tm = new ThreadMap<>(2);
    tm.put(1L, "hello");
    tm.put(2L, "world");

    tm.clear();
    tm.clear();
  }

}
