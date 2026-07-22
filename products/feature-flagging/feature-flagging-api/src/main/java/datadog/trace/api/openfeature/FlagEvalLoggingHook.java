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
 * <p>Contract: {@code finallyAfter} does ONLY cheap scalar extraction + a non-blocking offer to the
 * writer's bounded queue. No inline aggregation on the hook thread.
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
   * Cheap capture + non-blocking enqueue only. Runs at the {@code finally} stage so it covers
   * success, error, and default-value paths.
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

      // Cheap scalar extraction - no JSON, no map lookups beyond metadata.asMap()
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

  private Map<String, Value> snapshotAttrs(final HookContext<T> ctx) {
    if (ctx == null || ctx.getCtx() == null) {
      return Collections.emptyMap();
    }
    final EvaluationContext context = ctx.getCtx();
    final Map<String, Value> attrs = DDEvaluator.snapshotValues(context);
    attrs.remove(EvaluationContext.TARGETING_KEY);
    return attrs.isEmpty() ? Collections.emptyMap() : attrs;
  }

  /** Extracts converted, flattened attributes from the evaluation context. */
  private Map<String, Object> extractAttrs(final Map<String, Value> attrs) {
    if (attrs.isEmpty()) {
      return Collections.emptyMap();
    }
    return DDEvaluator.flattenValues(attrs);
  }
}
