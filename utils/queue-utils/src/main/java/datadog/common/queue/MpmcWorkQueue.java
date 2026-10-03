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
 * semantics. What matters here is that its {@code offer} can refuse a queue that is not full, and
 * that the bound does not live in the ring: a place is claimed before {@link #store} is ever
 * called, so a refusal can only mean not yet, and retrying will succeed. The retry is bounded
 * anyway — if the accounting were ever wrong, an unbounded spin would turn a bug into a hang. See
 * {@link Queues#mpmcArrayQueue} for why an ordinary caller cannot use the ring this way.
 */
final class MpmcWorkQueue<T> extends BaseWorkQueue<T> {

  /**
   * The smallest ring JCTools will build. A caller asking for one place gets two rather than an
   * {@link IllegalArgumentException}, in the spirit of rounding the capacity up.
   */
  private static final int MINIMUM_CAPACITY = 2;

  /**
   * How many times a claimed place will re-offer before giving up on it.
   *
   * <p>The window it is riding out is one thread's publish, so almost every retry that happens at
   * all succeeds on its next attempt. The yields are for the case the spin cannot fix — a producer
   * descheduled between claiming its slot and filling it — where spinning without giving the core
   * up would just burn the window down.
   */
  private static final int STORE_ATTEMPTS = 64;

  private static final int YIELD_EVERY = 16;

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
    // Small enough to inline into the admission path, which is the only reason the retry is a
    // separate method: a loop here made this too big for C2 to inline and cost every admission
    // more than the array ring was saving them.
    return queue.offer(element) || storeRetrying(element);
  }

  /**
   * The refusal is transient by construction — a place was claimed, so a slot exists. Reached
   * roughly once in four hundred offers with four producers and four consumers on a ring of eight,
   * and not at all on a linked queue, which never refuses.
   */
  private boolean storeRetrying(Object element) {
    for (int attempt = 1; attempt < STORE_ATTEMPTS; attempt++) {
      if (attempt % YIELD_EVERY == 0) {
        // The spin cannot help against a producer descheduled between claiming its slot and
        // filling it; giving the core up can.
        Thread.yield();
      }
      if (queue.offer(element)) {
        return true;
      }
    }
    return false;
  }

  @Override
  Object retrieve() {
    return queue.poll();
  }
}
