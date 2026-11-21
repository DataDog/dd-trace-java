package com.datadog.openfeature.internal.flagevaluation;

import java.util.Map;

/** Records flag evaluation events from the evaluation thread, without blocking. */
public interface FlagEvaluationWriter {
  /**
   * Reports whether the async hand-off queue currently has room for another event. Producers
   * consult this before performing any expensive context-copy work so a saturated queue is observed
   * as an O(1) read rather than a full snapshot followed by a discarded offer. Best-effort only:
   * the worker can drain (or a peer producer can fill) between this check and the subsequent
   * enqueue, so callers must still tolerate offer failure.
   *
   * @return {@code true} if the queue currently has room for another event.
   */
  boolean hasCapacityForEnqueue();

  /** Counts one queue-overflow drop without offering an event. */
  void countPreQueueOverflow();

  /**
   * Counts one evaluation whose context was truncated.
   *
   * @param reason the sorted, comma-separated set of the caps that fired.
   */
  void countContextTruncated(String reason);

  /**
   * Non-blocking enqueue of a flag evaluation event. Drops the event, and counts the drop, if the
   * queue is full.
   *
   * @param event the event to enqueue.
   */
  void enqueue(FlagEvalEvent event);

  /**
   * Records one evaluation from its fields. By default it enqueues an event; a writer that
   * aggregates on the evaluating thread records it without one.
   */
  default void record(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      final Map<String, Object> attrs,
      final Map<String, Object> features) {
    enqueue(
        new FlagEvalEvent(
            flagKey,
            variant,
            allocationKey,
            targetingKey,
            errorMessage,
            evalTimeMs,
            observeFullEvaluationData,
            attrs,
            features));
  }
}
