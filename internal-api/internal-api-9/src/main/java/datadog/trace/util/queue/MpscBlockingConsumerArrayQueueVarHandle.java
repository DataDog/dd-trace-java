package datadog.trace.util.queue;

import java.util.concurrent.locks.LockSupport;

/**
 * JCtools-like MpscBlockingConsumerArrayQueue implemented without Unsafe.
 *
 * <p>It features nonblocking offer/poll methods and blocking (condition based) take/put.
 */
public class MpscBlockingConsumerArrayQueueVarHandle<E> extends MpscArrayQueueVarHandle<E> {
  /** Consumer thread reference for wake-up. */
  private volatile Thread consumerThread;

  public MpscBlockingConsumerArrayQueueVarHandle(int capacity) {
    super(capacity);
  }

  @Override
  public boolean offer(E e) {
    final boolean success = super.offer(e);
    if (success) {
      Thread c = consumerThread;
      if (c != null) LockSupport.unpark(c);
    }
    return success;
  }

  public void put(E e) throws InterruptedException {
    // in this variant we should not use a blocking put since we do not support blocking producers
    throw new UnsupportedOperationException();
  }

  /**
   * Retrieves and removes the head element, waiting if necessary until one becomes available.
   *
   * @return the next element (never null)
   * @throws InterruptedException if interrupted while waiting
   */
  public E take() throws InterruptedException {
    consumerThread = Thread.currentThread();
    while (true) {
      E e = poll();
      if (e != null) return e;

      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      // Block until producer unparks us
      LockSupport.park(this);
    }
  }
}
