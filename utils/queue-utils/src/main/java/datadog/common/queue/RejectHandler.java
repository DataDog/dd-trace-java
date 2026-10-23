package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * Receives source elements a batch could not admit.
 *
 * <p>A producer returning null declines an element, so the handler is not called for it. If
 * capacity runs out before the producer is invoked, the handler may also receive elements the
 * producer would have declined. Reapply that rule before resubmitting them.
 *
 * @param <E> the source element type
 * @see WorkQueue#tryPutBatch(java.util.Collection, Object, BiContextualProducer, RejectHandler)
 */
@Strategy
@FunctionalInterface
public interface RejectHandler<E> {
  /** Called on the admitting thread, once per source element that could not be admitted. */
  void onRejected(E element);
}
