package com.datadog.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;
import java.util.function.Supplier;

class FlagEvalMetricsHook implements Hook<Object> {

  private final FlagEvalMetrics metrics;
  private final Supplier<DDEvaluator> evaluatorSupplier;

  FlagEvalMetricsHook(FlagEvalMetrics metrics) {
    this(metrics, () -> null);
  }

  FlagEvalMetricsHook(
      final FlagEvalMetrics metrics, final Supplier<DDEvaluator> evaluatorSupplier) {
    this.metrics = metrics;
    this.evaluatorSupplier = evaluatorSupplier;
  }

  @Override
  public void finallyAfter(
      HookContext<Object> ctx, FlagEvaluationDetails<Object> details, Map<String, Object> hints) {
    if (metrics == null || details == null) {
      return;
    }
    try {
      final DDEvaluator evaluator = evaluatorSupplier.get();
      if (evaluator != null && ctx != null) {
        evaluator.recordDependencyMetrics(
            evaluator.dependencyEvaluationTrace(ctx.getFlagKey(), ctx.getCtx()), metrics);
      }
    } catch (final RuntimeException | LinkageError ignored) {
      // Dependency metrics are best effort and must not suppress the root metric.
    }
    try {
      String flagKey = details.getFlagKey();
      String variant = details.getVariant();
      String reason = details.getReason();
      ErrorCode errorCode = details.getErrorCode();

      String allocationKey = null;
      ImmutableMetadata metadata = details.getFlagMetadata();
      if (metadata != null) {
        allocationKey = metadata.getString("allocationKey");
      }

      metrics.record(flagKey, variant, reason, errorCode, allocationKey);
    } catch (final RuntimeException | LinkageError ignored) {
      // Never let metrics recording break flag evaluation
    }
  }
}
