package com.datadog.openfeature.internal.evaluation;

/** The flag metadata keys the evaluator writes for the provider hooks and exposure hooks. */
public final class MetadataKeys {
  /** The key of the selected allocation. */
  public static final String ALLOCATION_KEY = "allocationKey";

  /** The evaluation timestamp, in milliseconds since epoch. */
  public static final String EVAL_TIMESTAMP_MS = "__dd_eval_timestamp_ms";

  /**
   * The consent to send the full evaluation data, stamped on every evaluation. A missing key means
   * a non-Datadog provider.
   */
  public static final String OBSERVE_FULL_EVALUATION_DATA = "observe_full_evaluation_data";

  /**
   * The serial id of the selected split, emitted with {@link #DO_LOG} and omitted when the split
   * has none.
   */
  public static final String SPLIT_SERIAL_ID = "__dd_split_serial_id";

  /**
   * Whether the selected allocation logs exposures, emitted when span enrichment is on or the
   * allocation logs exposures.
   */
  public static final String DO_LOG = "__dd_do_log";

  /**
   * Emitted only when the allocation logs exposures: true when this subject's exposure was already
   * recorded, so it is not sent again.
   */
  public static final String EXPOSURE_CACHE_HIT = "__dd_exposure_cache_hit";

  /** The prefix of the split features delivered to hooks. This is a public contract. */
  public static final String FEATURE_PREFIX = "__dd_feature.";

  /** The prefix of the split features sent with exposures. */
  public static final String EXPOSURE_FEATURE_PREFIX = "__dd_exposure_feature.";

  /** The prefix of the split features sent with flag evaluations. */
  public static final String EVALUATION_FEATURE_PREFIX = "__dd_evaluation_feature.";

  /**
   * Present only when the split routes a feature to flag evaluations, so the flag evaluation hook
   * can skip scanning the metadata on every other evaluation.
   */
  public static final String HAS_EVALUATION_FEATURES = "__dd_has_evaluation_features";

  private MetadataKeys() {}
}
