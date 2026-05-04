package datadog.trace.api.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;

class FlagEvalHook implements Hook<Object> {

  private final FlagEvalMetrics metrics;

  FlagEvalHook(FlagEvalMetrics metrics) {
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
        allocationKey = metadata.getString("allocationKey");
      }

      metrics.record(flagKey, variant, reason, errorCode, allocationKey);
    } catch (Exception e) {
      // Never let metrics recording break flag evaluation
    }
  }
}
