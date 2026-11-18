package com.datadog.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;
import java.util.Optional;
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
  public Optional<EvaluationContext> before(HookContext<Object> ctx, Map<String, Object> hints) {
    final DDEvaluator evaluator = evaluatorSupplier.get();
    if (evaluator != null && ctx != null) {
      final Object scope =
          evaluator.currentDependencyTelemetryScope(ctx.getFlagKey(), ctx.getCtx());
      if (scope != null) {
        HookDataAccessor.set(ctx, DDEvaluator.DEPENDENCY_TELEMETRY_SCOPE, scope);
      }
    }
    return Optional.empty();
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
            evaluator.dependencyEvaluationTrace(
                evaluator.dependencyTelemetryToken(scope(ctx, hints))),
            metrics);
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

  private Object scope(final HookContext<?> context, final Map<String, Object> hints) {
    final Object scope = HookDataAccessor.get(context, DDEvaluator.DEPENDENCY_TELEMETRY_SCOPE);
    if (scope != null || HookDataAccessor.isAvailable() || context == null) {
      return scope;
    }
    final DDEvaluator evaluator = evaluatorSupplier.get();
    return evaluator == null ? null : evaluator.legacyLifecycleScope(context.getFlagKey(), hints);
  }
}
