package datadog.common.queue;

import datadog.trace.api.function.Strategy;

/**
 * Sees the source elements a batch admission could not take, for a caller that has somewhere to put
 * them: a resubmission list, a spill buffer, a per-kind counter.
 *
 * <p>A handler rather than a returned collection, so a caller that only wanted the count is charged
 * nothing for one it would have thrown away, and a caller that wants the elements chooses where
 * they go instead of receiving a list it has to copy out of. The admission side's answer to {@link
 * ExceptionHandler}, which does the same thing for a consumer's failures.
 *
 * <p>Only refusals reach a handler. An element the producer declined by returning {@code null} was
 * the caller's own decision and is not a rejection. The queue cannot hold that line perfectly at
 * the boundary, though: a place is claimed before the producer is asked, so once the queue is full
 * a handler sees source elements the producer would have declined, indistinguishable from the rest.
 * A caller resubmitting what it is handed should apply its own decline rule again.
 *
 * @see WorkQueue#tryPutBatch(java.util.Collection, Object, BiContextualProducer, RejectHandler)
 */
@Strategy
@FunctionalInterface
public interface RejectHandler<E> {
  /** Called on the admitting thread, once per source element that could not be admitted. */
  void onRejected(E element);
}
