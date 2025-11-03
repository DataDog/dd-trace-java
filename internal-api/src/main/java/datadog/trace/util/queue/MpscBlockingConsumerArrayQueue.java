package datadog.trace.util.queue;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * JCtools-like MpscBlockingConsumerArrayQueue implemented without Unsafe.
 *
 * <p>It features nonblocking offer/poll methods and blocking (condition based) take/put.
 */
public class MpscBlockingConsumerArrayQueue<E> extends MpscArrayQueue<E> {
  // Blocking controls
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private final Condition notFull = lock.newCondition();

  public MpscBlockingConsumerArrayQueue(int capacity) {
    super(capacity);
  }

  @Override
  public boolean offer(E e) {
    final boolean success = super.offer(e);
    if (success) {
      signalNotEmpty();
    }
    return success;
  }

  public void put(E e) throws InterruptedException {
    while (!offer(e)) {
      awaitNotFull();
    }
  }

  @Override
  public E poll() {
    final E ret = super.poll();
    if (ret != null) {
      signalNotFull();
    }
    return ret;
  }

  public E take() throws InterruptedException {
    E e;
    while ((e = poll()) == null) {
      awaitNotEmpty();
    }
    return e;
  }

  /**
   * Fills the queue with elements provided by the supplier until either: - the queue is full, or -
   * the supplier runs out of elements (returns null)
   *
   * @param supplier a supplier of elements
   * @param limit maximum number of elements to attempt to insert
   * @return number of elements successfully enqueued
   */
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

  private void signalNotEmpty() {
    lock.lock();
    try {
      notEmpty.signal();
    } finally {
      lock.unlock();
    }
  }

  private void signalNotFull() {
    lock.lock();
    try {
      notFull.signal();
    } finally {
      lock.unlock();
    }
  }

  private void awaitNotEmpty() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      while (isEmpty()) {
        notEmpty.await();
      }
    } finally {
      lock.unlock();
    }
  }

  private void awaitNotFull() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      while (size() == capacity) {
        notFull.await();
      }
    } finally {
      lock.unlock();
    }
  }
}
