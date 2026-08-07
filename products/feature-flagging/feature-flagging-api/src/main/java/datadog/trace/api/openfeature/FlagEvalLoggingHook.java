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
 * <p>Hot-path cost: DDEvaluator.copyPrunedContext performs one bounded walk of the caller-owned
 * EvaluationContext, applying every retained-size cap inline so work is proportional to what is
 * kept, never to what the caller supplied. The returned map is capped by field count, key length,
 * value length, list width, structure width, and depth.
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

      // eval-time: from flag metadata "dd.eval.timestamp_ms" (Long), fallback to hook-fire time.
      // ImmutableMetadata.getLong available since sdk 1.4+.
      final Long evalTimeObj = metadata != null ? metadata.getLong("dd.eval.timestamp_ms") : null;
      final long evalTimeMs = evalTimeObj != null ? evalTimeObj : System.currentTimeMillis();

      // variant: the OpenFeature variant key (same source as the OTel FlagEvalMetricsHook), NOT the
      // evaluated value. A null variant means no variant was selected (runtime default).
      final String variant = details.getVariant();

      // error message: prefer the human-readable message; fall back to the error code name when
      // the message is empty (some providers populate only the code). null on success.
      String errorMessage = details.getErrorMessage();
      if ((errorMessage == null || errorMessage.isEmpty()) && details.getErrorCode() != null) {
        errorMessage = details.getErrorCode().name();
      }
      if (errorMessage != null && errorMessage.isEmpty()) {
        errorMessage = null;
      }

      // targetingKey from evaluation context
      final String targetingKey =
          ctx != null && ctx.getCtx() != null ? ctx.getCtx().getTargetingKey() : null;

      // Bounded copy of the caller's mutable context (see DDEvaluator#copyPrunedContext for
      // every retained-size cap). Runs inline because the event is consumed asynchronously and
      // the source context is caller-owned; work is proportional to what is retained.
      final DDEvaluator.CopyResult copy =
          ctx != null && ctx.getCtx() != null
              ? DDEvaluator.copyPrunedContext(ctx.getCtx())
              : new DDEvaluator.CopyResult(Collections.emptyMap(), null);

      if (copy.truncatedReason != null) {
        w.countContextTruncated(copy.truncatedReason);
      }

      w.enqueue(
          new FlagEvalEvent(
              flagKey, variant, allocationKey, targetingKey, errorMessage, evalTimeMs, copy.attrs));
    } catch (LinkageError | Exception e) {
      // Never let EVP recording break flag evaluation
    }
  }
}
