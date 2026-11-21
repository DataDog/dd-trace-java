package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * Decides what happens to an item whose consumer threw.
 *
 * <p>Invoked only on failure — a successful consumption needs no callback. The return value reports
 * the decision; it does not report whether the item will eventually succeed. Logging and counting
 * are the caller's to compose here: this API performs neither.
 */
@Strategy
@FunctionalInterface
public interface RetryStrategy<T> {
  /**
   * @param attempt how many times this item has been consumed unsuccessfully, including now, so the
   *     first failure reports {@code 1}
   * @return {@code true} if the item was resubmitted, {@code false} if the strategy gave up
   */
  boolean onFailure(T item, int attempt, Throwable failure, RetryQueue<T> retryQueue);

  /**
   * Resubmits a failed item up to {@code maxRetries} times, then gives up. The count is retries,
   * not consumptions: {@code maxRetries(3)} lets an item be consumed four times in all, and {@code
   * maxRetries(0)} never resubmits.
   *
   * <p>Hold the result in a {@code static final} field, or call this inline at the processing call
   * site; either way the strategy's exact class is visible to the compiler.
   *
   * @param <T> the item type
   * @param maxRetries how many times an item may be resubmitted
   * @return a strategy that retries at most {@code maxRetries} times
   */
  static <T> RetryStrategy<T> maxRetries(int maxRetries) {
    return new MaxRetries<>(maxRetries);
  }
}
