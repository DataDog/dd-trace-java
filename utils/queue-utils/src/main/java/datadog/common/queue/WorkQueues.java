package datadog.common.queue;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Factory methods for {@link WorkQueue} buffers: bounded handoff points that count what they drop
 * and never build an element they are going to reject.
 *
 * <p>Distinct from {@link Queues}, which hands back a raw JCTools queue for the caller to drive
 * itself. A buffer created here owns its backing — which implementation it is stays an
 * implementation detail, so a call site can be re-backed without changing.
 */
public final class WorkQueues {

  private WorkQueues() {}

  /**
   * Creates a bounded Multiple Producer, Single Consumer buffer backed by an MPSC array queue.
   *
   * <p>The preferred backing: no per-element node, constant-time {@link WorkQueue#size()}, and
   * admission that claims a slot before invoking a producer, so an element that will not fit is
   * never built.
   *
   * @param requestedCapacity the bound. Will be rounded to the next power of two.
   */
  public static <E> WorkQueue<E> createMpscQueue(int requestedCapacity) {
    return new MpscWorkQueue<>(requestedCapacity);
  }

  /**
   * Creates a bounded Multiple Producer, Multiple Consumer buffer backed by a {@link
   * ConcurrentLinkedQueue}.
   *
   * <p>For call sites that need several consumers. It keeps the linked queue's per-element node, so
   * it buys the admission and lifecycle contract, an enforceable bound and a constant-time {@link
   * WorkQueue#size()}, but not the allocation win — prefer {@link #createMpscQueue} where a single
   * consumer is possible.
   *
   * @param capacity the bound
   */
  public static <E> WorkQueue<E> createMpmcQueue(int capacity) {
    return new LinkedWorkQueue<>(capacity);
  }

  /**
   * Creates an unbounded Multiple Producer, Multiple Consumer buffer backed by a {@link
   * ConcurrentLinkedQueue}.
   *
   * <p>Unbounded means admission never rejects and {@link WorkQueue#dropped()} only ever counts
   * items abandoned by a retry strategy. Intended as a migration step for call sites that are
   * unbounded today: adopt the interface here, then pick a bound and move to {@link
   * #createMpscQueue}.
   */
  public static <E> WorkQueue<E> createUnboundedMpmcQueue() {
    return new LinkedWorkQueue<>(Integer.MAX_VALUE);
  }
}
