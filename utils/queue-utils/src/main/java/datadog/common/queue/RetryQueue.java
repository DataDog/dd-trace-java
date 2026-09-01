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
   * <p>The failed item's place was given back when it was consumed, so this claims a place like any
   * other admission and can be rejected if the queue filled up behind it. A refusal here is one
   * step of the strategy's decision, not its outcome; {@link RetryStrategy#onFailure} returning
   * {@code false} is what says the item was finally given up on. This is the overload every
   * ordinary strategy wants: it resubmits without allocating the array the varargs form needs.
   *
   * @return whether the item was resubmitted
   */
  boolean retry(T item);

  /**
   * Resubmits several items in place of the failed item.
   *
   * <p>Each piece claims its own place, so a partition can be admitted only in part, and the return
   * value reports whether all of them made it. As with the single-item overload, a refusal is not
   * counted here; a strategy that partially resubmits and returns {@code true} is telling the queue
   * the remainder was its own to lose.
   *
   * @return whether every item was resubmitted
   */
  @SuppressWarnings("unchecked")
  boolean retry(T... items);
}
