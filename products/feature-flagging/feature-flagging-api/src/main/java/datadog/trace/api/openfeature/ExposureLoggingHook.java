package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;

/**
 * Sends the exposure for a logged allocation to the agent once the evaluation has succeeded.
 *
 * <p>It runs at the finally stage because OpenFeature runs provider hooks first: an after hook
 * added by the application could still throw and turn the result into the default value. At the
 * finally stage the details carry that error, so a failed evaluation sends no exposure and does not
 * mark the subject as exposed.
 */
class ExposureLoggingHook<T> implements Hook<T> {

  static final ExposureLoggingHook<Object> INSTANCE =
      new ExposureLoggingHook<>(ExposureDeduplicationCache.INSTANCE);

  private final ExposureDeduplicationCache cache;

  ExposureLoggingHook(final ExposureDeduplicationCache cache) {
    this.cache = cache;
  }

  @Override
  public void finallyAfter(
      final HookContext<T> ctx,
      final FlagEvaluationDetails<T> details,
      final Map<String, Object> hints) {
    try {
      if (details == null || details.getErrorCode() != null) {
        return;
      }
      final ImmutableMetadata metadata = details.getFlagMetadata();
      if (metadata == null
          || !Boolean.TRUE.equals(metadata.getBoolean(DDEvaluator.METADATA_DO_LOG))
          || !Boolean.FALSE.equals(metadata.getBoolean(DDEvaluator.METADATA_EXPOSURE_CACHE_HIT))) {
        return;
      }
      final String flag = details.getFlagKey();
      final String allocationKey = metadata.getString("allocationKey");
      final String variantKey = details.getVariant();
      final EvaluationContext context = ctx == null ? null : ctx.getCtx();
      if (allocationKey == null || variantKey == null || context == null) {
        return;
      }
      final Integer serialId =
          DDEvaluator.exposureSerialId(metadata.getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
      cache.record(flag, context.getTargetingKey(), allocationKey, variantKey, serialId);
      FeatureFlaggingGateway.dispatch(
          DDEvaluator.exposureEvent(flag, allocationKey, variantKey, context, serialId));
    } catch (LinkageError e) {
      // Never let exposure reporting break flag evaluation.
    }
  }
}
