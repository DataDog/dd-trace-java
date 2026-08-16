package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenFeature Hook that captures flag evaluation events for EVP flagevaluation emission.
 *
 * <p>Contract: finallyAfter does scalar metadata extraction, a single bounded copy of the
 * evaluation context, and a non-blocking offer to the writer's bounded queue. Aggregation and
 * posting are deferred to the writer's worker thread.
 *
 * <p>Hot-path cost: under consent-on (observeFullEvaluationData=true) DDEvaluator.copyPrunedContext
 * performs one bounded walk of the caller-owned EvaluationContext, applying every retained-size cap
 * inline so work is proportional to what is kept, never to what the caller supplied. The returned
 * map is capped by field count, key length, value length, list width, structure width, and depth.
 * Under consent-off the context is dropped on emit, so the copy is skipped entirely and the hot
 * path is scalar-only.
 *
 * <p>This hook is registered alongside the existing OTel FlagEvalMetricsHook - it does NOT replace
 * it.
 *
 * <p>The writer is resolved lazily on each call, so the hook is always safe to register - if the
 * writer is absent (killswitch off or not yet started) it is a no-op.
 */
class FlagEvalLoggingHook<T> implements Hook<T> {

  /**
   * Singleton instance: always registered when the provider is created; harmless when writer=null
   * (killswitch off or not yet started).
   */
  static final FlagEvalLoggingHook<Object> INSTANCE = new FlagEvalLoggingHook<>();

  /**
   * Writer resolver. Production instances resolve through FeatureFlaggingGateway; tests can inject
   * a direct writer or a resolver that simulates old-bootstrap linkage failures.
   */
  private final Supplier<FlagEvaluationWriter> writerSupplier;

  /** Production constructor - resolves writer from gateway. */
  FlagEvalLoggingHook() {
    this(FeatureFlaggingGateway::getFlagEvalWriter);
  }

  /** Test-only constructor - injects a writer directly, bypassing the gateway. */
  FlagEvalLoggingHook(final FlagEvaluationWriter writer) {
    this(() -> writer);
  }

  /** Test-only constructor - injects a writer resolver directly, bypassing the gateway. */
  FlagEvalLoggingHook(final Supplier<FlagEvaluationWriter> writerSupplier) {
    this.writerSupplier = writerSupplier;
  }

  /**
   * Capture + non-blocking enqueue only; no flattening, aggregation, or I/O. Runs at the finally
   * stage so it covers success, error, and default-value paths.
   *
   * <p>The context snapshot taken here scales with context size/nesting - see the class javadoc for
   * why it cannot be deferred.
   */
  @Override
  public void finallyAfter(
      final HookContext<T> ctx,
      final FlagEvaluationDetails<T> details,
      final Map<String, Object> hints) {
    try {
      if (details == null) {
        return;
      }
      if (!FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled()) {
        return;
      }

      final FlagEvaluationWriter w = writerSupplier.get();
      if (w == null) {
        return;
      }

      // Pre-queue guard: if the queue is already saturated, avoid the context-copy work whose
      // result would be discarded on offer(). Best-effort — the queue can still be full at
      // offer() time — but flips a full snapshot into an O(1) check on the drop path.
      if (!w.hasCapacityForEnqueue()) {
        w.countPreQueueOverflow();
        return;
      }

      // Scalar extraction - individual typed metadata reads, no JSON, no flattening
      final String flagKey = details.getFlagKey();
      final ImmutableMetadata metadata = details.getFlagMetadata();

      // allocationKey: "allocationKey" (camelCase) - consistent with FlagEvalMetricsHook.java
      final String allocationKey = metadata != null ? metadata.getString("allocationKey") : null;

      // eval-time: from flag metadata "__dd_eval_timestamp_ms" (Long), fallback to hook-fire time.
      // ImmutableMetadata.getLong available since sdk 1.4+.
      final Long evalTimeObj = metadata != null ? metadata.getLong("__dd_eval_timestamp_ms") : null;
      final long evalTimeMs = evalTimeObj != null ? evalTimeObj : System.currentTimeMillis();

      // variant: the OpenFeature variant key (same source as the OTel FlagEvalMetricsHook), NOT the
      // evaluated value. A null variant means no variant was selected (runtime default).
      final String variant = details.getVariant();

      // targetingKey from evaluation context
      final String targetingKey =
          ctx != null && ctx.getCtx() != null ? ctx.getCtx().getTargetingKey() : null;

      // Consent is read from metadata stamped by DDEvaluator (pinned to its ServerConfiguration).
      // Missing key = non-DD provider → false, the privacy-preserving default.
      final Boolean consentFromMetadata =
          metadata != null
              ? metadata.getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA)
              : null;
      final boolean observeFullEvaluationData = consentFromMetadata != null && consentFromMetadata;

      // Error message: prefer the human-readable message under consent-on; under consent-off the
      // provider's raw message can echo evaluation-context values (e.g. NumberFormatException:
      // "For input string: \"jane.doe@...\""), so replace it with the ErrorCode name — a stable,
      // PII-free signal. Same substitution path is used when the message is absent regardless of
      // consent (some providers populate only the code). Null on success.
      String errorMessage = observeFullEvaluationData ? details.getErrorMessage() : null;
      if ((errorMessage == null || errorMessage.isEmpty()) && details.getErrorCode() != null) {
        errorMessage = details.getErrorCode().name();
      }
      if (errorMessage != null && errorMessage.isEmpty()) {
        errorMessage = null;
      }

      // On the protected path (consent-off) the evaluation context is dropped on emit and never
      // consulted by the aggregator, so skip the bounded copy entirely — the copy cost only
      // applies under consent-on.
      final Map<String, Object> attrs;
      final long estimatedContextRetainedBytes;
      if (observeFullEvaluationData && ctx != null && ctx.getCtx() != null) {
        // Bounded copy of the caller's mutable context (see DDEvaluator.copyPrunedContext for
        // every retained-size cap). Runs inline because the event is consumed asynchronously
        // and the source context is caller-owned; work is proportional to what is retained.
        final DDEvaluator.CopyResult copy = DDEvaluator.copyPrunedContext(ctx.getCtx());
        if (copy.truncatedReason != null) {
          w.countContextTruncated(copy.truncatedReason);
        }
        attrs = copy.attrs;
        estimatedContextRetainedBytes = copy.estimatedRetainedBytes;
      } else {
        attrs = Collections.emptyMap();
        estimatedContextRetainedBytes = 0;
      }

      w.enqueue(
          new FlagEvalEvent(
              flagKey,
              variant,
              allocationKey,
              targetingKey,
              errorMessage,
              evalTimeMs,
              observeFullEvaluationData,
              attrs,
              estimatedContextRetainedBytes));
    } catch (LinkageError e) {
      // Never let EVP recording break flag evaluation
    }
  }
}
