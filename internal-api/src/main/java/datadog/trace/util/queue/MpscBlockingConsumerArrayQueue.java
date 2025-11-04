package datadog.trace.util.queue;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JCtools-like MpscBlockingConsumerArrayQueue implemented without Unsafe.
 *
 * <p>It features nonblocking offer/poll methods and blocking (condition based) take/put.
 */
public class MpscBlockingConsumerArrayQueue<E> extends MpscArrayQueue<E>
    implements BlockingQueue<E> {
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

  @Override
  public int drainTo(Collection<? super E> c) {
    return drainTo(c, Integer.MAX_VALUE);
  }

  @Override
  public int drainTo(Collection<? super E> c, int maxElements) {
    return drain(c::add, maxElements);
  }
}
