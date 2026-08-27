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

  /** Creates the slot inside the claimed place, and hands it back to the reserving thread. */
  private static final class SlotSupplier<T> implements MessagePassingQueue.Supplier<Object> {
    Slot<T> slot;

    @Override
    public Object get() {
      slot = new Slot<>();
      return slot;
    }
  }

  private final MessagePassingQueue<Object> queue;

  /**
   * Set before the first {@link Slot} can reach the array, and never cleared. A queue whose caller
   * never reserves keeps the plain consumption path; one that has reserved even once pays a peek
   * and a type test per item forever, which is the price of not taxing every other call site.
   */
  private volatile boolean reservations;

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
  Slot<T> reserve() {
    // Set first: a slot must never reach the array before the consumer knows to expect one.
    reservations = true;
    SlotSupplier<T> supplier = new SlotSupplier<>();
    return queue.fill(supplier, 1) == 1 ? supplier.slot : null;
  }

  @Override
  Object take() {
    if (!reservations) {
      return queue.poll();
    }
    for (; ; ) {
      Object head = queue.relaxedPeek();
      if (!(head instanceof Slot)) {
        // Either empty, or an ordinary element whose place was never reserved.
        return head == null ? null : queue.poll();
      }
      Object element = ((Slot<?>) head).element();
      if (element == null) {
        // Still being built. The place is claimed, so there is nothing behind it to take either.
        return null;
      }
      queue.poll();
      if (element != Slot.RELEASED) {
        return element;
      }
      // Abandoned without ever being filled: skip it and look at what is behind it.
    }
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
