package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;

import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the exposure of an allocation logging exposures once the evaluation has succeeded, and
 * sends it to Datadog when it is new or changed.
 *
 * <p>It runs at the finally stage because OpenFeature runs provider hooks first: an after hook
 * added by the application could still throw and turn the result into the default value. At the
 * finally stage the details carry that error, so a failed evaluation sends no exposure and does not
 * mark the subject as exposed.
 */
class ExposureLoggingHook<T> implements Hook<T> {

  private static final Logger log = LoggerFactory.getLogger(ExposureLoggingHook.class);

  /** Evaluator resolver, returning {@code null} when the provider has no Datadog evaluator. */
  private final Supplier<DDEvaluator> evaluatorSupplier;

  private final AtomicBoolean warned = new AtomicBoolean();

  ExposureLoggingHook(final Supplier<DDEvaluator> evaluatorSupplier) {
    this.evaluatorSupplier = evaluatorSupplier;
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
      if (metadata == null) {
        return;
      }
      // The ImmutableMetadata getters build a debug message for every absent key.
      final Map<String, Object> values = metadata.asUnmodifiableMap();
      if (!Boolean.TRUE.equals(values.get(DO_LOG))
          || !Boolean.FALSE.equals(values.get(EXPOSURE_CACHE_HIT))) {
        return;
      }
      final Object allocationValue = values.get(ALLOCATION_KEY);
      final String allocationKey =
          allocationValue instanceof String ? (String) allocationValue : null;
      final String variantKey = details.getVariant();
      final EvaluationContext context = ctx == null ? null : ctx.getCtx();
      if (allocationKey == null || variantKey == null || context == null) {
        return;
      }
      final DDEvaluator evaluator = this.evaluatorSupplier.get();
      final ExposureDeduplicationCache cache = evaluator == null ? null : evaluator.exposureCache();
      if (cache == null) {
        return;
      }
      final String flag = details.getFlagKey();
      final Object serialValue = values.get(SPLIT_SERIAL_ID);
      final Integer serialId = serialValue instanceof Integer ? (Integer) serialValue : null;
      // The exposure is recorded even when it is not sent, so exposure hooks get the same
      // decisions whether or not exposures go to Datadog.
      if (cache.recordIfChanged(
              flag, context.getTargetingKey(), allocationKey, variantKey, serialId)
          && evaluator.isExposureDeliveryEnabled()) {
        evaluator.recordExposure(
            DDEvaluator.exposureEvent(
                flag,
                allocationKey,
                variantKey,
                context,
                serialId,
                SplitFeatureMetadata.featuresWithPrefix(metadata, EXPOSURE_FEATURE_PREFIX)));
      }
    } catch (final RuntimeException | LinkageError e) {
      // Never let exposure reporting break flag evaluation.
      if (this.warned.compareAndSet(false, true)) {
        log.warn("Could not record a feature flag exposure; later failures are not logged", e);
      }
    }
  }
}
