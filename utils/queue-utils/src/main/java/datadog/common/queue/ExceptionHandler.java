package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * Deals with a consumer's failure and lets the item go, for a caller who wants to see what went
 * wrong without deciding whether to try again. The item is dropped either way, and the failure does
 * not reach the caller of {@code process}.
 *
 * <p>The narrow half of {@link RetryStrategy}: reach for that one when the answer to a failure is
 * sometimes "again", and this one when it is only ever "record it and move on".
 *
 * @see WorkQueue#process(java.util.function.Consumer, ExceptionHandler)
 */
@Strategy
@FunctionalInterface
public interface ExceptionHandler {
  /**
   * Called on the consuming thread, in place of propagating. A handler that throws propagates in
   * the failure's stead.
   */
  void handle(Throwable failure);
}
