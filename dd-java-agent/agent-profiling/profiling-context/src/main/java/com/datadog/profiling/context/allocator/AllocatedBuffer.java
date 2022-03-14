package com.datadog.profiling.context.allocator;

import com.datadog.profiling.context.LongIterator;

/**
 * Represents the memory allocated by a {@linkplain Allocators} implementation. The buffer defines a
 * few rudimentary operations to store and retrieve long values (both sequential and positional
 * stores are supported) as well as a custom iterator over the long values stored in the buffer.
 */
public interface AllocatedBuffer {
  /** Release the held memory */
  void release();

  /** @return the buffer capacity in bytes */
  int capacity();

  /**
   * Add the long value to the buffer
   *
   * @param value the long value to store
   * @return {@literal true} if the buffer was able to store the value, {@literal false} otherwise
   *     (eg. when the buffer capacity is exhausted)
   */
  boolean putLong(long value);

  /**
   * Puts the long value to the buffer at the given position
   *
   * @param pos the position to store the value at
   * @param value the long value to store
   * @return {@literal true} if the buffer was able to store the value, {@literal false} otherwise
   *     (eg. when the buffer capacity is exhausted)
   */
  boolean putLong(int pos, long value);

  /**
   * Retrieves the long value from the given position
   *
   * @param pos the position to retrieve the long value from; it is upon the caller to make sure the
   *     position was previously written to and is within the capacity
   * @return the long value stored at that position; when a read from behind the capacity limit is
   *     attempted {@linkplain Long#MIN_VALUE} is always returned
   */
  long getLong(int pos);

  /** @return a custom long-value iterator of all values stored in the buffer up to this point */
  LongIterator iterator();
}
