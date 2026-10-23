package datadog.common.queue;

import org.jctools.queues.MessagePassingQueue;

/**
 * A third concrete {@link BaseWorkQueue}, existing only so a benchmark can put three types into the
 * profile of {@code store} and {@code retrieve}.
 *
 * <p>It is a real backing rather than a stub — an SPSC ring, driven from one thread — because a
 * stub whose {@code store} folded away would measure the dispatch and not the cliff. Kept in the
 * benchmark source set: the claim in {@link BaseWorkQueue#store} is about what a third backing
 * would cost, and shipping one to prove it would make the claim false.
 */
final class ThirdBackingWorkQueue<T> extends BaseWorkQueue<T> {

  private final MessagePassingQueue<Object> queue;

  ThirdBackingWorkQueue(int requestedCapacity) {
    this(Queues.<Object>spscArrayQueue(requestedCapacity));
  }

  private ThirdBackingWorkQueue(MessagePassingQueue<Object> queue) {
    super(queue.capacity());
    this.queue = queue;
  }

  @Override
  boolean store(Object element) {
    return queue.offer(element);
  }

  @Override
  Object retrieve() {
    return queue.poll();
  }
}
