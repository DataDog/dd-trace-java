package datadog.common.queue;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jctools.queues.MessagePassingQueue;

/**
 * A {@link WorkQueue} for several consumers: an MPMC array queue when bounded, a {@link
 * ConcurrentLinkedQueue} when not.
 *
 * <p>Storage only, as with {@link MpscWorkQueue} — the bound and the reserve-before-construct
 * guarantee live in {@link BaseWorkQueue}. One class covers both structures on purpose. They are
 * different enough to want different types, but {@code store} and {@code retrieve} are the sites
 * every admission and every drain funnels through, and a third implementation of those makes them
 * megamorphic for callers that only ever touch one backing — see {@link BaseWorkQueue#store}. A
 * predictable branch on a final field inside this class is much the cheaper way to hold two
 * structures, and only multi-consumer callers reach it at all.
 *
 * <p>Bounded is array-backed because that is the better structure: measured against the linked
 * queue at 12.7ns against 20.6ns per admit-and-drain, and no per-element node, so it stops
 * manufacturing 24 bytes of garbage per element. Unbounded stays linked because JCTools' unbounded
 * MPMC queue exists only in its {@code Unsafe} form, and {@link Queues} deliberately moves off
 * {@code Unsafe} on Java 25 and later.
 *
 * <p>The bounded backing is a JCTools MPMC ring; consult the JCTools documentation for its
 * semantics. Its strict {@code offer} refuses only when the ring's indexes say it is full, and
 * places outstanding never exceed its capacity, so {@link #store} cannot be refused: a slot a
 * consumer has claimed but not yet released is waited out inside {@code offer}, not reported.
 */
final class MpmcWorkQueue<T> extends BaseWorkQueue<T> {

  /**
   * The smallest ring JCTools will build. A caller asking for one place gets two rather than an
   * {@link IllegalArgumentException}, in the spirit of rounding the capacity up.
   */
  private static final int MINIMUM_CAPACITY = 2;

  private final Queue<Object> queue;

  /** Bounded, and array-backed: the reason this class exists. */
  @SuppressWarnings("unchecked")
  static <E> MpmcWorkQueue<E> bounded(int requestedCapacity) {
    MessagePassingQueue<Object> ring =
        Queues.mpmcArrayQueue(Math.max(MINIMUM_CAPACITY, requestedCapacity));
    // Every JCTools array queue is an AbstractQueue; the cast is checked once, here, and never on
    // the admission path. The bound is the capacity the ring actually rounded up to, not the one
    // that was asked for, so the counter and the ring can never disagree about what full means.
    return new MpmcWorkQueue<>((Queue<Object>) ring, ring.capacity());
  }

  /**
   * Unbounded, for call sites that have no defensible capacity yet. Admission never rejects, so the
   * queue buys the lifecycle and the interface and nothing else; it is a step on the way to picking
   * a bound, not a destination.
   */
  static <E> MpmcWorkQueue<E> unbounded() {
    return new MpmcWorkQueue<>(new ConcurrentLinkedQueue<>(), Integer.MAX_VALUE);
  }

  private MpmcWorkQueue(Queue<Object> queue, int capacity) {
    super(capacity);
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
