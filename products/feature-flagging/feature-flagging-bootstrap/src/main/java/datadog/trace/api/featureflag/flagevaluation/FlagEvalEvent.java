package datadog.trace.api.featureflag.flagevaluation;

import java.util.Collections;
import java.util.Map;

/**
 * Lightweight data record capturing a single flag evaluation for EVP flagevaluation emission.
 *
 * <p>This is the currency passed from the FlagEvalLoggingHook (feature-flagging-api) to the
 * FlagEvaluationWriter (feature-flagging-lib) via a non-blocking bounded queue.
 *
 * <p>Scalar fields and context attributes are captured at hook-fire time on the evaluation thread.
 * No aggregation happens here.
 */
public final class FlagEvalEvent {

  /** The feature flag key. Never null. */
  public final String flagKey;

  /**
   * The OpenFeature variant key selected for the evaluation. Null means the default value was
   * returned (runtime default).
   */
  public final String variant;

  /** The allocation key from flag metadata ("allocationKey"). May be null. */
  public final String allocationKey;

  /** The targeting key from the evaluation context. May be null. */
  public final String targetingKey;

  /**
   * The evaluation error message when the evaluation failed, else null. Sourced from the
   * OpenFeature evaluation details (error message, falling back to the error code).
   */
  public final String errorMessage;

  /**
   * Evaluation timestamp in milliseconds since epoch. Stamped at eval-entry time from flag metadata
   * key __dd_eval_timestamp_ms, or falls back to hook-fire time when absent. This ensures
   * first/last_evaluation reflect evaluation time, not hook-fire time.
   */
  public final long evalTimeMs;

  /**
   * Flattened evaluation context attributes. Used for the full-tier canonical context key. May be
   * empty but never null.
   */
  public final Map<String, Object> attrs;

  /**
   * PII consent from the ServerConfiguration used by the evaluation. When false (privacy-preserving
   * default), the targeting key is hashed and the per-evaluation context is omitted on emission.
   */
  public final boolean observeFullEvaluationData;

  /**
   * Estimated retained bytes for {@link #attrs}. A negative value means the writer must estimate
   * the map as a compatibility fallback.
   */
  public final long estimatedContextRetainedBytes;

  /** Convenience constructor; consent defaults to the privacy-preserving false. */
  public FlagEvalEvent(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final long evalTimeMs,
      final Map<String, Object> attrs) {
    this(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        null,
        evalTimeMs,
        false,
        attrs,
        FlagEvalEventMemoryEstimator.UNKNOWN_RETAINED_BYTES);
  }

  /** Convenience constructor; consent defaults to the privacy-preserving false. */
  public FlagEvalEvent(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final Map<String, Object> attrs) {
    this(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        errorMessage,
        evalTimeMs,
        false,
        attrs,
        FlagEvalEventMemoryEstimator.UNKNOWN_RETAINED_BYTES);
  }

  public FlagEvalEvent(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      final Map<String, Object> attrs) {
    this(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        errorMessage,
        evalTimeMs,
        observeFullEvaluationData,
        attrs,
        FlagEvalEventMemoryEstimator.UNKNOWN_RETAINED_BYTES);
  }

  public FlagEvalEvent(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      final Map<String, Object> attrs,
      final long estimatedContextRetainedBytes) {
    this.flagKey = flagKey;
    this.variant = variant;
    this.allocationKey = allocationKey;
    this.targetingKey = targetingKey;
    this.errorMessage = errorMessage;
    this.evalTimeMs = evalTimeMs;
    this.observeFullEvaluationData = observeFullEvaluationData;
    this.attrs = attrs != null ? attrs : Collections.emptyMap();
    this.estimatedContextRetainedBytes = estimatedContextRetainedBytes;
  }
}
