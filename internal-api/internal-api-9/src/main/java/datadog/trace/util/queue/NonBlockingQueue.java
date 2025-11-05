package datadog.trace.util.queue;

import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public interface NonBlockingQueue<E> extends Queue<E> {
  int drain(Consumer<E> consumer);

  int drain(Consumer<E> consumer, int limit);

  int fill(@Nonnull Supplier<? extends E> supplier, int limit);

  int remainingCapacity();

  int capacity();
}
