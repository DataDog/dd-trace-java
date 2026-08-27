package datadog.common.queue;

import org.jctools.queues.MessagePassingQueue;

/**
 * A {@link WorkQueue} over a JCTools MPSC array queue: many producers, one consumer, bounded by
 * construction with no per-element node.
 *
 * <p>Reserve-before-construct is the backing queue's own {@code fill(Supplier, 1)}, which
 * CAS-claims the slot and only then calls the supplier, returning zero without ever calling it when
 * there is no room. That makes admission exact rather than best-effort: a rejected element is not
 * merely discarded cheaply, it is never built.
 */
final class MpscWorkQueue<T> extends BaseWorkQueue<T> {

  /**
   * Handed to {@code fill} so the producer runs inside the claimed slot. One small short-lived
   * object per producing admission, which never escapes the {@code fill} call and so is a candidate
   * for scalar replacement; the payload it defers building is the allocation that matters.
   */
  private static final class ProducingSupplier<C, T>
      implements MessagePassingQueue.Supplier<Object> {
    private final C context;
    private final ContextualProducer<? super C, ? extends T> producer;

    ProducingSupplier(C context, ContextualProducer<? super C, ? extends T> producer) {
      this.context = context;
      this.producer = producer;
    }

    @Override
    public Object get() {
      return producer.produce(context);
    }
  }

  private final MessagePassingQueue<Object> queue;

  MpscWorkQueue(int requestedCapacity) {
    this.queue = Queues.mpscArrayQueue(requestedCapacity);
  }

  @Override
  boolean admit(Object element) {
    return queue.offer(element);
  }

  @Override
  <C> boolean admit(C context, ContextualProducer<? super C, ? extends T> producer) {
    return queue.fill(new ProducingSupplier<>(context, producer), 1) == 1;
  }

  @Override
  Object take() {
    return queue.poll();
  }

  @Override
  void discardAll() {
    queue.clear();
  }

  @Override
  public int size() {
    return queue.size();
  }

  int capacity() {
    return queue.capacity();
  }
}
