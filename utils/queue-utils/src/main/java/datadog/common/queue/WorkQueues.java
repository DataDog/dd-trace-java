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
   * Creates a bounded array-backed queue for multiple producers and one consumer.
   *
   * <p>Use one consumer thread for all operations that remove elements, including processing,
   * {@link WorkQueue#clear()} and {@link WorkQueue#shutdown()}. Concurrent consumers can spin
   * indefinitely. Use {@link #createMpmcQueue(int)} when several consumers are needed.
   *
   * @param <E> the element type
   * @param requestedCapacity capacity, rounded up to a power of two
   * @return a queue requiring one consumer
   */
  public static <E> WorkQueue<E> createMpscQueue(int requestedCapacity) {
    return new MpscWorkQueue<>(requestedCapacity);
  }

  /**
   * Creates a bounded array-backed queue for multiple producers and consumers.
   *
   * <p>Prefer {@link #createMpscQueue(int)} when one consumer is sufficient.
   *
   * @param <E> the element type
   * @param requestedCapacity capacity, rounded up to a power of two and at least two
   * @return a queue that allows concurrent consumers
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
