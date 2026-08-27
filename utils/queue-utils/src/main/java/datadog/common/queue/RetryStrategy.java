package datadog.common.queue;

/**
 * Decides what happens to an item whose consumer threw.
 *
 * <p>Invoked only on failure — a successful consumption needs no callback. The return value reports
 * the decision; it does not report whether the item will eventually succeed. Logging and counting
 * are the caller's to compose here: this API performs neither.
 */
@FunctionalInterface
public interface RetryStrategy<T> {
  /**
   * @param attempt how many times this item has been consumed unsuccessfully, including now, so the
   *     first failure reports {@code 1}
   * @return {@code true} if the item was resubmitted, {@code false} if the strategy gave up
   */
  boolean onFailure(T item, int attempt, Throwable failure, RetryQueue<T> retryQueue);
}
