package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.AllocatedBuffer;

public interface Allocator {
  int getChunkSize();

  AllocatedBuffer allocate(int capacity);

  AllocatedBuffer allocateChunks(int chunks);
}
