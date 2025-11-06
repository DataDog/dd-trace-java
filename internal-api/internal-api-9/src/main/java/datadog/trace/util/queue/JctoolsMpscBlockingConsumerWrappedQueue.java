package datadog.trace.util.queue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

public class JctoolsMpscBlockingConsumerWrappedQueue<E> extends JctoolsWrappedQueue<E>
    implements BlockingConsumerNonBlockingQueue<E> {

  private final BlockingQueue<E> blockingQueueDelegate;

  public JctoolsMpscBlockingConsumerWrappedQueue(
      @Nonnull MpscBlockingConsumerArrayQueue<E> delegate) {
    super(delegate);
    this.blockingQueueDelegate = delegate;
  }

  @Override
  public E take() throws InterruptedException {
    return blockingQueueDelegate.take();
  }

  @Override
  public E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    return blockingQueueDelegate.poll(timeout, unit);
  }
}
