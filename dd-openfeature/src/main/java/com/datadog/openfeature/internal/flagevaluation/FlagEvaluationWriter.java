package com.datadog.openfeature.internal.flagevaluation;

import java.util.Map;

/** Records flag evaluations on the evaluating thread, without blocking on I/O. */
public interface FlagEvaluationWriter {
  /**
   * Counts one evaluation whose context was truncated.
   *
   * @param reason the sorted, comma-separated set of the caps that fired.
   */
  void countContextTruncated(String reason);

  /**
   * Records one evaluation into the writer's aggregate.
   *
   * @param flagKey the flag key.
   * @param variant the selected variant, or {@code null} for the runtime default.
   * @param allocationKey the allocation key, if any.
   * @param targetingKey the subject's targeting key, if any.
   * @param errorMessage the evaluation error, if any.
   * @param evalTimeMs the evaluation time, in milliseconds since the epoch.
   * @param observeFullEvaluationData whether the raw targeting key and context may be sent.
   * @param attrs the pruned evaluation context; ignored without consent. The writer keeps it.
   * @param features the split features routed to evaluations. The writer keeps it.
   */
  void record(
      String flagKey,
      String variant,
      String allocationKey,
      String targetingKey,
      String errorMessage,
      long evalTimeMs,
      boolean observeFullEvaluationData,
      Map<String, Object> attrs,
      Map<String, Object> features);
}
