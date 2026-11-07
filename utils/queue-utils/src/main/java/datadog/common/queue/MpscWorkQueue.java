package datadog.common.queue;

import org.jctools.queues.MessagePassingQueue;

/**
 * A {@link WorkQueue} over a JCTools MPSC array queue: many producers, one consumer, no per-element
 * node. The preferred backing.
 *
 * <p>Storage only. The bound and the reserve-before-construct guarantee both live in {@link
 * BaseWorkQueue}, which spends a place before it calls any producer, so by the time an element
 * reaches {@link #store} the ring is known to have room for it.
 *
 * <p>That the ring could have enforced its own bound, inside a CAS it was performing anyway, is the
 * cost of this arrangement — see {@link BaseWorkQueue} for what it buys. What it avoids is holding
 * a ring position open across a caller-controlled gap: the ring reports a claimed-but-unfilled
 * position as empty, so a reservation that held one would stall the consumer, and would need a
 * placeholder object per reservation for the consumer to tell an abandoned position from a pending
 * one.
 */
final class MpscWorkQueue<T> extends BaseWorkQueue<T> {

  private final MessagePassingQueue<Object> queue;

  MpscWorkQueue(int requestedCapacity) {
    this(Queues.<Object>mpscArrayQueue(requestedCapacity));
  }

  /** Takes the queue already built, so the bound can be the capacity it actually rounded up to. */
  private MpscWorkQueue(MessagePassingQueue<Object> queue) {
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
