package datadog.common.queue;

import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

public interface BlockingConsumerNonBlockingQueue<E> extends NonBlockingQueue<E> {
  E poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException;

  E take() throws InterruptedException;
}
