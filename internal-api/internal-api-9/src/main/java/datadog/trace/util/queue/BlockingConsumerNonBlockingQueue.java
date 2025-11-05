package datadog.trace.util.queue;

import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

public interface BlockingConsumerNonBlockingQueue<E> extends NonBlockingQueue<E> {
  boolean offer(E e, long timeout, @Nonnull TimeUnit unit) throws InterruptedException;

  E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException;

  void put(E e) throws InterruptedException;

  E take() throws InterruptedException;
}
