package datadog.common.queue;

import static datadog.trace.util.BitUtils.nextPowerOfTwo;

import datadog.common.queue.padding.PaddedSequence;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Base class for non-blocking queuing operations.
 *
 * @param <E> the type of elements held by this queue
 */
abstract class BaseQueue<E> extends AbstractQueue<E> implements NonBlockingQueue<E> {
  private static final int CACHE_LINE_LONGS = 8; // 64 bytes / 8 bytes per long/ref
  protected static final VarHandle ARRAY_HANDLE;

  static {
    try {
      ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  /** The capacity of the queue (must be a power of two) */
  protected final int capacity;

  /** Mask for fast modulo operation (index = pos & mask) */
  protected final int mask;

  /** The backing array (plain Java array for VarHandle access) */
  protected final Object[] buffer;

  // Padding to avoid false sharing
  @SuppressWarnings("unused")
  private long p0, p1, p2, p3, p4, p5, p6;

  /** Next free slot for producer (single-threaded) */
  protected final PaddedSequence tail = new PaddedSequence();

  /** Next slot to consume (multi-threaded) */
  protected final PaddedSequence head = new PaddedSequence();

  // Padding around head
  @SuppressWarnings("unused")
  private long r0, r1, r2, r3, r4, r5, r6;

  public BaseQueue(int requestedCapacity) {
    this.capacity = nextPowerOfTwo(requestedCapacity);
    this.mask = this.capacity - 1;
    this.buffer = new Object[capacity + 2 * CACHE_LINE_LONGS];
  }

  protected final int arrayIndex(long sequence) {
    return (int) (sequence & mask) + CACHE_LINE_LONGS;
  }

  @Override
  public int drain(Consumer<E> consumer) {
    return drain(consumer, Integer.MAX_VALUE);
  }

  @Override
  public int drain(@Nonnull Consumer<E> consumer, int limit) {
    int count = 0;
    E e;
    while (count < limit && (e = poll()) != null) {
      consumer.accept(e);
      count++;
    }
    return count;
  }

  @Override
  public int fill(@Nonnull Supplier<? extends E> supplier, int limit) {
    if (limit <= 0) {
      return 0;
    }

    int added = 0;
    while (added < limit) {
      E e = supplier.get();
      if (e == null) {
        break; // stop if supplier exhausted
      }

      if (offer(e)) {
        added++;
      } else {
        break; // queue is full
      }
    }
    return added;
  }

  /**
   * Iterator is not supported.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public final Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public final int remainingCapacity() {
    return capacity - size();
  }

  @Override
  public final int capacity() {
    return capacity;
  }

  @Override
  public final int size() {
    long currentTail = tail.getVolatile();
    long currentHead = head.getVolatile();
    return (int) (currentTail - currentHead);
  }
}
