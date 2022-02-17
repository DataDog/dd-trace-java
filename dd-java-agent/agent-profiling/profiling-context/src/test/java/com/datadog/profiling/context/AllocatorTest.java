package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AllocatorTest {
  private Allocator instance;

  @BeforeEach
  void setup() throws Exception {
    instance = Allocators.directAllocator(1024, 80);
  }

  @Test
  void allocate() {
    AllocatedBuffer allocatedBuffer = instance.allocate(800);
    assertNotNull(allocatedBuffer);
    assertTrue(allocatedBuffer.capacity() >= 800);
    AllocatedBuffer allocatedBuffer1 = instance.allocate(500);
    assertNotNull(allocatedBuffer1);
    assertTrue(allocatedBuffer1.capacity() < 500);
    AllocatedBuffer allocatedBuffer2 = instance.allocate(50);
    assertNull(allocatedBuffer2);
    allocatedBuffer.release();
    allocatedBuffer1.release();
  }

  @Test
  void release() {
  }
}
