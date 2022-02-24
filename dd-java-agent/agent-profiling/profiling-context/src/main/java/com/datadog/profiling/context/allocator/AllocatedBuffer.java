package com.datadog.profiling.context.allocator;

import com.datadog.profiling.context.LongIterator;

public interface AllocatedBuffer {
  void release();

  int capacity();

  boolean putLong(long value);

  boolean putLong(int pos, long value);

  long getLong(int pos);

  LongIterator iterator();
}
