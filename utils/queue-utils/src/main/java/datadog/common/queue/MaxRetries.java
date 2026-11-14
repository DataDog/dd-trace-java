package datadog.common.queue;

/**
 * The strategy behind {@link RetryStrategy#maxRetries}. A named class rather than a lambda so it
 * reads as itself in compilation logs.
 *
 * <p>{@link RetryStrategy#onFailure} reports the first failure as attempt {@code 1}, so the
 * comparison is against the failures so far rather than against the attempt number.
 */
final class MaxRetries<T> implements RetryStrategy<T> {
  private final int maxRetries;

  MaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  @Override
  public boolean onFailure(T item, int attempt, Throwable failure, RetryQueue<T> retryQueue) {
    return attempt <= maxRetries && retryQueue.retry(item);
  }
}
