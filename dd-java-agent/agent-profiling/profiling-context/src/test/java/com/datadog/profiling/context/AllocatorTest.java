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
    Allocator.ChunkBuffer chunkBuffer = instance.allocate(800);
    assertNotNull(chunkBuffer);
    assertTrue(chunkBuffer.capacity() >= 800);
    Allocator.ChunkBuffer chunkBuffer1 = instance.allocate(500);
    assertNotNull(chunkBuffer1);
    assertTrue(chunkBuffer1.capacity() < 500);
    Allocator.ChunkBuffer chunkBuffer2 = instance.allocate(50);
    assertNull(chunkBuffer2);
    chunkBuffer.release();
    chunkBuffer1.release();
  }

  @Test
  void release() {
  }
}
