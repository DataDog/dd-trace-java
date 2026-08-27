package datadog.common.queue;

/**
 * The capability to resubmit work after a consumer failure.
 *
 * <p>Only obtainable inside {@link RetryStrategy#onFailure}, never from a plain consumer, so
 * re-enqueue-after-failure stays visibly distinct from ordinary admission.
 */
public interface RetryQueue<T> {
  /**
   * Resubmits the failed item.
   *
   * <p>Reuses the lease the failed item already holds and so cannot fail on capacity. This is the
   * overload every ordinary strategy wants: it resubmits without allocating the array the varargs
   * form needs.
   *
   * @return whether the item was resubmitted
   */
  boolean retry(T item);

  /**
   * Resubmits several items in place of the failed item.
   *
   * <p>Partitioning failed work into smaller pieces needs slots beyond the one the failed item
   * holds, and is a no-op returning {@code false} if they cannot be reserved; the original item
   * stays leased and is retried later.
   *
   * @return whether the items were resubmitted
   */
  @SuppressWarnings("unchecked")
  boolean retry(T... items);
}
