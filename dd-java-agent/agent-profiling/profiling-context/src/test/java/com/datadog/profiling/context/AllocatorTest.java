package com.datadog.profiling.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AllocatorTest {
  private Allocator instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new Allocator(1024, 80);
  }

  @Test
  void allocate() {
    Allocator.Block block = instance.allocate(10);
    assertNotNull(block);
    Allocator.Block block1 = instance.allocate(5);
    assertNull(block1);
    block.release();
  }

  @Test
  void release() {
  }
}
