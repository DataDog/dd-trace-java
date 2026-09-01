package datadog.common.queue;

/**
 * A {@link RetryStrategy} that resubmits a failed item up to a fixed number of times, then gives
 * up.
 *
 * <p>The count is retries, not consumptions: {@code new MaxRetries<>(3)} lets an item be consumed
 * four times in all, and {@code new MaxRetries<>(0)} never resubmits. {@link
 * RetryStrategy#onFailure} reports the first failure as attempt {@code 1}, so the comparison is
 * against the failures so far rather than against the attempt number.
 */
public final class MaxRetries<T> implements RetryStrategy<T> {
  private final int maxRetries;

  public MaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  @Override
  public boolean onFailure(T item, int attempt, Throwable failure, RetryQueue<T> retryQueue) {
    return attempt <= maxRetries && retryQueue.retry(item);
  }
}
