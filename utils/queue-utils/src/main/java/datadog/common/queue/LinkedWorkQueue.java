package datadog.common.queue;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A {@link WorkQueue} over a {@link ConcurrentLinkedQueue}: multi-producer, multi-consumer,
 * optionally bounded.
 *
 * <p>This backing exists to give call sites that cannot yet take an MPSC ring — because they have
 * several consumers, or no defensible capacity — the admission and lifecycle contract anyway, so
 * they can be migrated behind {@link WorkQueue} first and re-backed later. It keeps the linked
 * queue's per-element node, so it does not deliver the allocation win; prefer {@link
 * MpscWorkQueue}.
 *
 * <p>Storage only: the bound lives in {@link BaseWorkQueue}, which is what replaces the hand-rolled
 * cap plus O(n) {@code ConcurrentLinkedQueue.size()} walk such a call site otherwise pays on every
 * admission, and makes {@link #size()} constant-time.
 */
final class LinkedWorkQueue<T> extends BaseWorkQueue<T> {

  private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();

  /**
   * @param capacity the bound, or {@link Integer#MAX_VALUE} to leave the queue unbounded
   */
  LinkedWorkQueue(int capacity) {
    super(capacity);
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
