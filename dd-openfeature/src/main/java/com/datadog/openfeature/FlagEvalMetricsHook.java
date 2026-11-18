package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;

class FlagEvalMetricsHook implements Hook<Object> {

  private final FlagEvalMetrics metrics;

  FlagEvalMetricsHook(FlagEvalMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  public void finallyAfter(
      HookContext<Object> ctx, FlagEvaluationDetails<Object> details, Map<String, Object> hints) {
    if (metrics == null || details == null) {
      return;
    }
    try {
      String flagKey = details.getFlagKey();
      String variant = details.getVariant();
      String reason = details.getReason();
      ErrorCode errorCode = details.getErrorCode();

      String allocationKey = null;
      ImmutableMetadata metadata = details.getFlagMetadata();
      if (metadata != null) {
        // The ImmutableMetadata getters build a debug message for every absent key.
        final Object allocationValue = metadata.asUnmodifiableMap().get(ALLOCATION_KEY);
        allocationKey = allocationValue instanceof String ? (String) allocationValue : null;
      }

      metrics.record(flagKey, variant, reason, errorCode, allocationKey);
    } catch (Exception e) {
      // Never let metrics recording break flag evaluation
    }
  }
}
