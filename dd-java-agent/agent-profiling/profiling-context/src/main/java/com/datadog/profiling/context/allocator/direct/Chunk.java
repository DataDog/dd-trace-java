package com.datadog.profiling.context.allocator.direct;

import java.nio.ByteBuffer;

final class Chunk {
  private final DirectAllocator allocator;
  final ByteBuffer buffer;
  private final int ref;
  private int len = 1;

  Chunk(DirectAllocator allocator, ByteBuffer buffer, int ref) {
    this.allocator = allocator;
    this.buffer = buffer;
    this.ref = ref;
  }

  void extend() {
    len++;
    buffer.limit(buffer.limit() + allocator.getChunkSize());
  }

  void release() {
    allocator.release(ref, len);
  }
}
