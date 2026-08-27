package datadog.common.queue;

/** A {@link RetryStrategy} that resubmits an item until a fixed attempt count is reached. */
public final class MaxRetries<T> implements RetryStrategy<T> {
  private final int maxRetries;

  public MaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  @Override
  public boolean onFailure(T item, int attempt, Throwable failure, RetryQueue<T> retryQueue) {
    return attempt < maxRetries && retryQueue.retry(item);
  }
}
