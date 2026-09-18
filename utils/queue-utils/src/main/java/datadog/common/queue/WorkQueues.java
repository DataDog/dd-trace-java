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
   * <p>Single Consumer is a requirement, not a characteristic. Producers may be any number of
   * threads, but every call that takes elements out -- any {@code process}, {@code processOrRetry}
   * or {@code processOrHandle} overload, plus {@link WorkQueue#clear} and {@link
   * WorkQueue#shutdown} -- must come from one thread. A second consumer is not rejected and does
   * not throw: the two can spin inside the ring's gap-wait indefinitely, which presents as a hang
   * rather than a failure. Use {@link #createMpmcQueue} where more than one thread drains.
   *
   * @param requestedCapacity the bound. Will be rounded to the next power of two.
   */
  public static <E> WorkQueue<E> createMpscQueue(int requestedCapacity) {
    return new MpscWorkQueue<>(requestedCapacity);
  }

  /**
   * Creates a bounded Multiple Producer, Multiple Consumer buffer backed by an MPMC array queue.
   *
   * <p>For call sites that need several consumers. No per-element node, so it costs an admission
   * and a drain about what the MPSC ring does; prefer {@link #createMpscQueue} anyway where a
   * single consumer is possible, because the MPSC ring is cheaper still and does not have to ride
   * out the MPMC ring's transient refusals — see {@link MpmcWorkQueue} for what those are and why
   * claiming a place first makes them harmless.
   *
   * @param requestedCapacity the bound. Will be rounded to the next power of two, and raised to two
   *     if it is less than that.
   */
  public static <E> WorkQueue<E> createMpmcQueue(int requestedCapacity) {
    return MpmcWorkQueue.bounded(requestedCapacity);
  }

  /**
   * Creates an unbounded Multiple Producer, Multiple Consumer buffer backed by a {@link
   * ConcurrentLinkedQueue}.
   *
   * <p>Unbounded means admission never rejects, so the only element this can lose is one a retry
   * strategy gives up on. Intended as a migration step for call sites that are unbounded today:
   * adopt the interface here, then pick a bound and move to {@link #createMpscQueue}.
   *
   * <p>Linked rather than array-backed because there is no capacity to size an array from. JCTools'
   * unbounded MPMC queue would avoid the per-element node, but exists only in an {@code Unsafe}
   * form, which {@link Queues} deliberately steps away from on Java 25 and later.
   */
  public static <E> WorkQueue<E> createUnboundedMpmcQueue() {
    return MpmcWorkQueue.unbounded();
  }
}
