package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Value;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenFeature {@code Hook<T>} that captures flag evaluation events for EVP {@code flagevaluation}
 * emission.
 *
 * <p>Contract: {@code finallyAfter} does scalar metadata extraction, a deep snapshot of the
 * evaluation context, and a non-blocking offer to the writer's bounded queue. Flattening,
 * aggregation, and posting are deferred to the writer's worker thread.
 *
 * <p><strong>Hot-path cost:</strong> the context snapshot is NOT free. {@link
 * DDEvaluator#snapshotValues} deep-copies every context attribute (a fresh {@code Value} per
 * attribute, plus {@code ArrayList}/{@code ImmutableStructure} copies for nested lists and
 * structures, plus an {@code IdentityHashMap} for cycle detection) synchronously on the evaluation
 * thread. Cost is proportional to the size and nesting depth of the evaluation context, so it is
 * measurable for large or deeply nested contexts. Some copy is required - {@link EvaluationContext}
 * is caller-owned and mutable, so its values must be captured before the event is handed to another
 * thread - but this one is unbounded: the writer later prunes the context to 256 fields, so a
 * narrower snapshot could cap the inline cost. Only the {@link DDEvaluator#flattenValues} step is
 * deferred, via the event's attribute supplier.
 *
 * <p>This hook is registered alongside the existing OTel {@link FlagEvalMetricsHook} - it does NOT
 * replace it (the existing OTel metrics hook is left unchanged).
 *
 * <p>The writer is resolved lazily from {@link FeatureFlaggingGateway#getFlagEvalWriter()} on each
 * call, so the hook is always safe to register - if the writer is absent (killswitch off or not yet
 * started) it is a no-op.
 */
class FlagEvalLoggingHook<T> implements Hook<T> {

  /**
   * Singleton instance: always registered when the provider is created; harmless when writer=null
   * (killswitch off or not yet started).
   */
  static final FlagEvalLoggingHook<Object> INSTANCE = new FlagEvalLoggingHook<>();

  /**
   * Writer resolver. Production instances resolve through {@link FeatureFlaggingGateway}; tests can
   * inject a direct writer or a resolver that simulates old-bootstrap linkage failures.
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
   * Capture + non-blocking enqueue only; no flattening, aggregation, or I/O. Runs at the {@code
   * finally} stage so it covers success, error, and default-value paths.
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

      // Deep-copies the caller's mutable context. This is the dominant cost of the hook for
      // large/nested contexts; it must happen inline, before the async handoff below.
      final Map<String, Value> attrs = snapshotAttrs(ctx);

      w.enqueue(
          new FlagEvalEvent(
              flagKey,
              variant,
              allocationKey,
              targetingKey,
              errorMessage,
              evalTimeMs,
              () -> extractAttrs(attrs)));
    } catch (LinkageError | Exception e) {
      // Never let EVP recording break flag evaluation
    }
  }

  /**
   * Deep-copies the evaluation context's attributes on the calling (evaluation) thread. Runs inline
   * because {@link EvaluationContext} is caller-owned and mutable and the resulting event is
   * consumed asynchronously. Cost scales with attribute count and nesting depth.
   */
  private Map<String, Value> snapshotAttrs(final HookContext<T> ctx) {
    if (ctx == null || ctx.getCtx() == null) {
      return Collections.emptyMap();
    }
    final EvaluationContext context = ctx.getCtx();
    final Map<String, Value> attrs = DDEvaluator.snapshotValues(context);
    attrs.remove(EvaluationContext.TARGETING_KEY);
    return attrs.isEmpty() ? Collections.emptyMap() : attrs;
  }

  /**
   * Extracts converted, flattened attributes from the snapshot. Invoked lazily by the writer's
   * worker thread via the event's supplier, never on the evaluation thread.
   */
  private Map<String, Object> extractAttrs(final Map<String, Value> attrs) {
    if (attrs.isEmpty()) {
      return Collections.emptyMap();
    }
    return DDEvaluator.flattenValues(attrs);
  }
}
