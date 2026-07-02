package datadog.trace.api.featureflag.flagevaluation;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Lightweight data record capturing a single flag evaluation for EVP flagevaluation emission.
 *
 * <p>This is the currency passed from the {@code FlagEvalLoggingHook} (feature-flagging-api) to the
 * {@code FlagEvaluationWriter} (feature-flagging-lib) via a non-blocking bounded queue.
 *
 * <p>Scalar fields are captured at hook-fire time on the evaluation thread. Context attributes can
 * be supplied lazily so recursive flattening happens on the writer thread, not on the evaluation
 * path. No aggregation happens here.
 */
public final class FlagEvalEvent {

  /** The feature flag key. Never null. */
  public final String flagKey;

  /**
   * The OpenFeature variant key selected for the evaluation. {@code null} means the default value
   * was returned (runtime default).
   */
  public final String variant;

  /** The allocation key from flag metadata ("allocationKey"). May be null. */
  public final String allocationKey;

  /** The targeting key from the evaluation context. May be null. */
  public final String targetingKey;

  /**
   * The evaluation error message when the evaluation failed, else {@code null}. Sourced from the
   * OpenFeature evaluation details (error message, falling back to the error code).
   */
  public final String errorMessage;

  /**
   * Evaluation timestamp in milliseconds since epoch. Stamped at eval-entry time from flag metadata
   * key {@code "dd.eval.timestamp_ms"}, or falls back to hook-fire time when absent. This ensures
   * first/last_evaluation reflect evaluation time, not hook-fire time.
   */
  public final long evalTimeMs;

  /**
   * Flattened evaluation context attributes. Used for the full-tier canonical context key. May be
   * empty but never null.
   */
  public final Map<String, Object> attrs;

  private final Supplier<Map<String, Object>> attrsSupplier;

  public FlagEvalEvent(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final long evalTimeMs,
      final Map<String, Object> attrs) {
    this(flagKey, variant, allocationKey, targetingKey, null, evalTimeMs, attrs);
  }

  public FlagEvalEvent(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final Map<String, Object> attrs) {
    this.flagKey = flagKey;
    this.variant = variant;
    this.allocationKey = allocationKey;
    this.targetingKey = targetingKey;
    this.errorMessage = errorMessage;
    this.evalTimeMs = evalTimeMs;
    this.attrs = attrs != null ? attrs : Collections.emptyMap();
    this.attrsSupplier = null;
  }

  public FlagEvalEvent(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final Supplier<Map<String, Object>> attrsSupplier) {
    this.flagKey = flagKey;
    this.variant = variant;
    this.allocationKey = allocationKey;
    this.targetingKey = targetingKey;
    this.errorMessage = errorMessage;
    this.evalTimeMs = evalTimeMs;
    this.attrs = Collections.emptyMap();
    this.attrsSupplier = attrsSupplier;
  }

  public Map<String, Object> contextAttributes() {
    if (attrsSupplier == null) {
      return attrs;
    }
    final Map<String, Object> supplied = attrsSupplier.get();
    return supplied != null ? supplied : Collections.emptyMap();
  }
}
