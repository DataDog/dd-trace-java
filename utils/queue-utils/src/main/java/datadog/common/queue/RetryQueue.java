package datadog.common.queue;

/**
 * The capability to resubmit work after a consumer failure.
 *
 * <p>Only obtainable inside {@link RetryStrategy#onFailure}, never from a plain consumer, so
 * re-enqueue-after-failure stays visibly distinct from ordinary admission.
 */
public interface RetryQueue<T> {
  /**
   * Resubmits one or more items in place of the failed item.
   *
   * <p>Resubmitting a single item reuses the lease the failed item already holds and so cannot fail
   * on capacity. Resubmitting several — partitioning failed work into smaller pieces — needs the
   * additional slots, and is a no-op returning {@code false} if they cannot be reserved; the
   * original item stays leased and is retried later.
   *
   * @return whether the items were resubmitted
   */
  @SuppressWarnings("unchecked")
  boolean retry(T... items);
}
