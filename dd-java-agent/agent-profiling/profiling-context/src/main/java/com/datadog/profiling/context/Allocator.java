package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.AllocatedBuffer;

/** A simple allocator definition */
public interface Allocator {
  /**
   * The size of the chunk the allocator opearates with
   *
   * @return size of the chunk the allocator opearates with
   */
  int getChunkSize();

  /**
   * Allocate the {@code size} number of bytes aligned to the chunk size
   *
   * @param size the number of bytes to allocate
   * @return the allocated byffer or {@literal null} when allocation fails
   */
  AllocatedBuffer allocate(int size);

  /**
   * Allocate the {@code chunks} number of chunks
   *
   * @param chunks the number of chunks to allocate
   * @return the allocated byffer or {@literal null} when allocation fails
   */
  AllocatedBuffer allocateChunks(int chunks);
}
