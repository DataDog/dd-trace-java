package com.datadog.profiling.context.allocator;

import com.datadog.profiling.context.Allocator;
import com.datadog.profiling.context.allocator.direct.DirectAllocator;
import com.datadog.profiling.context.allocator.heap.HeapAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Allocators {
  private static final Logger log = LoggerFactory.getLogger(Allocators.class);

  protected Allocators() {}

  public static Allocator directAllocator(int capacity, int chunk) {
    return new DirectAllocator(capacity, chunk);
  }

  public static Allocator heapAllocator(int capacity, int chunk) {
    return new HeapAllocator(capacity, chunk);
  }
}
