package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Subject;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;

/** OpenFeature hook that dispatches exposure events for allocations with {@code doLog=true}. */
class ExposureHook<T> implements Hook<T> {

  static final ExposureHook<Object> INSTANCE = new ExposureHook<>();

  @Override
  public void after(
      final HookContext<T> context,
      final FlagEvaluationDetails<T> details,
      final Map<String, Object> hints) {
    try {
      if (details == null) {
        return;
      }
      final ImmutableMetadata metadata = details.getFlagMetadata();
      if (metadata == null
          || !Boolean.TRUE.equals(metadata.getBoolean(DDEvaluator.METADATA_DO_LOG))) {
        return;
      }
      final String allocationKey = metadata.getString("allocationKey");
      final String variantKey = details.getVariant();
      final EvaluationContext evaluationContext = context != null ? context.getCtx() : null;
      if (allocationKey == null || variantKey == null || evaluationContext == null) {
        return;
      }
      final Long evaluationTimestamp = metadata.getLong(DDEvaluator.METADATA_EVAL_TIMESTAMP_MS);
      final long timestamp =
          evaluationTimestamp != null ? evaluationTimestamp : System.currentTimeMillis();
      FeatureFlaggingGateway.dispatch(
          new ExposureEvent(
              timestamp,
              new datadog.trace.api.featureflag.exposure.Allocation(allocationKey),
              new datadog.trace.api.featureflag.exposure.Flag(details.getFlagKey()),
              new datadog.trace.api.featureflag.exposure.Variant(variantKey),
              new Subject(
                  evaluationContext.getTargetingKey(),
                  DDEvaluator.flattenContext(evaluationContext))));
    } catch (final LinkageError | Exception ignored) {
      // Never let exposure recording break flag evaluation.
    }
  }
}
