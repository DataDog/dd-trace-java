package datadog.common.queue;

import static org.jctools.util.Pow2.roundToPowerOfTwo;

import datadog.common.queue.padding.PaddedLong;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Iterator;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueueUtil;

/**
 * Base class for VarHandle-based lock-free queues with optimized memory ordering.
 *
 * @param <E> element type
 */
abstract class BaseQueue<E> extends AbstractQueue<E> implements MessagePassingQueue<E> {
  // 128-byte padding for Apple Silicon, prefetch, and future CPUs
  private static final int CACHE_LINE_LONGS = 16;

  protected static final VarHandle ARRAY_HANDLE;

  static {
    try {
      ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  protected final int capacity;
  protected final int mask;
  protected final Object[] buffer;
  protected final PaddedLong tail = new PaddedLong(); // producer index
  protected final PaddedLong head = new PaddedLong(); // consumer index

  /**
   * @param requestedCapacity queue capacity (rounded up to power of two)
   */
  public BaseQueue(int requestedCapacity) {
    this.capacity = roundToPowerOfTwo(requestedCapacity);
    this.mask = this.capacity - 1;
    this.buffer = new Object[capacity + 2 * CACHE_LINE_LONGS];
  }

  /**
   * Converts sequence to array index: (sequence & mask) + padding offset
   *
   * @param sequence sequence number
   * @return array index with padding offset
   */
  protected final int arrayIndex(long sequence) {
    return (int) (sequence & mask) + CACHE_LINE_LONGS;
  }

  /**
   * @param consumer element consumer
   * @return count drained
   */
  @Override
  public final int drain(Consumer<E> consumer) {
    return MessagePassingQueueUtil.drain(this, consumer);
  }

  @Override
  public final Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }

  /**
   * @param c element consumer
   * @param wait wait strategy
   * @param exit exit condition
   */
  @Override
  public final void drain(Consumer<E> c, WaitStrategy wait, ExitCondition exit) {
    MessagePassingQueueUtil.drain(this, c, wait, exit);
  }

  /**
   * @param s element supplier
   * @param wait wait strategy
   * @param exit exit condition
   */
  @Override
  public final void fill(Supplier<E> s, WaitStrategy wait, ExitCondition exit) {
    MessagePassingQueueUtil.fill(this, s, wait, exit);
  }

  /**
   * @param s element supplier
   * @return count filled
   */
  @Override
  public final int fill(Supplier<E> s) {
    return MessagePassingQueueUtil.fillBounded(this, s);
  }

  /**
   * @return queue capacity
   */
  @Override
  public final int capacity() {
    return capacity;
  }

  /**
   * Returns estimated size. May be stale due to concurrent updates. Uses sandwich technique: read
   * head, tail, head to detect races.
   *
   * @return estimated queue size
   */
  @Override
  public final int size() {
    long after = head.getVolatile();
    long size;
    while (true) {
      final long before = after;
      final long currentTail = tail.getVolatile();
      after = head.getVolatile();
      // "Sandwich" pattern (head-tail-head) to detect races.
      // If head unchanged, tail read is consistent. If head changed, consumer
      // polled concurrently - retry. Prevents negative/stale size from reordering.
      if (before == after) {
        size = currentTail - after;
        break;
      }
    }
    return sanitizeSize(size);
  }

  /**
   * Conservative empty check: may return false when poll() would return null, but never returns
   * true when elements exist.
   *
   * @return true if empty (conservative)
   */
  @Override
  public final boolean isEmpty() {
    return head.getVolatile() >= tail.getVolatile();
  }

  private int sanitizeSize(long size) {
    if (size < 0) return 0;
    if (size > capacity) return capacity;
    if (size > Integer.MAX_VALUE) return Integer.MAX_VALUE;
    return (int) size;
  }

  @Override
  public final String toString() {
    return this.getClass().getName();
  }
}
