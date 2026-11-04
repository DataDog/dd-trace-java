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
import dev.openfeature.sdk.Reason;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  public Optional<EvaluationContext> before(
      final HookContext<T> ctx, final Map<String, Object> hints) {
    try {
      final DDEvaluator evaluator = this.evaluatorSupplier.get();
      if (evaluator != null) {
        evaluator.beginDependencyExposureScope(
            ctx == null ? null : ctx.getFlagKey(), ctx == null ? null : ctx.getCtx());
      }
    } catch (final RuntimeException | LinkageError e) {
      warnOnce(e);
    }
    return Optional.empty();
  }

  @Override
  public void finallyAfter(
      final HookContext<T> ctx,
      final FlagEvaluationDetails<T> details,
      final Map<String, Object> hints) {
    try {
      final DDEvaluator evaluator = this.evaluatorSupplier.get();
      final DDEvaluator.DependencyTelemetryToken token =
          evaluator == null ? null : evaluator.dependencyTelemetryToken(details);
      final List<com.datadog.openfeature.internal.evaluation.FlagEvaluator.DependencyExposure>
          dependencyExposures =
              token == null
                  ? java.util.Collections
                      .<com.datadog.openfeature.internal.evaluation.FlagEvaluator
                              .DependencyExposure>
                          emptyList()
                  : evaluator.endDependencyExposureScope(token);
      if (evaluator != null && token == null) {
        evaluator.endUnclaimedDependencyExposureScope();
      }
      if (details == null) {
        log.trace("Feature Flagging exposure suppressed: evaluation failed or details unavailable");
        return;
      }
      if (details.getErrorCode() != null || Reason.ERROR.name().equals(details.getReason())) {
        log.trace("Feature Flagging exposure suppressed: evaluation failed or details unavailable");
        return;
      }
      final EvaluationContext context = ctx == null ? null : ctx.getCtx();
      if (evaluator != null) {
        try {
          evaluator.recordDependencyExposures(dependencyExposures, context);
        } catch (final RuntimeException | LinkageError e) {
          // Dependency exposure delivery is best-effort and must not suppress the root exposure.
          warnOnce(e);
        }
      }
      final ImmutableMetadata metadata = details.getFlagMetadata();
      if (metadata == null) {
        log.trace("Feature Flagging exposure suppressed: metadata unavailable");
        return;
      }
      // The ImmutableMetadata getters build a debug message for every absent key.
      final Map<String, Object> values = metadata.asUnmodifiableMap();
      if (!Boolean.TRUE.equals(values.get(DO_LOG))) {
        log.trace("Feature Flagging exposure suppressed: allocation does not log exposures");
        return;
      }
      if (!Boolean.FALSE.equals(values.get(EXPOSURE_CACHE_HIT))) {
        log.trace("Feature Flagging exposure suppressed: exposure already recorded");
        return;
      }
      final Object allocationValue = values.get(ALLOCATION_KEY);
      final String allocationKey =
          allocationValue instanceof String ? (String) allocationValue : null;
      final String variantKey = details.getVariant();
      if (allocationKey == null || variantKey == null || context == null) {
        log.trace(
            "Feature Flagging exposure suppressed: allocation, variant, or context unavailable");
        return;
      }
      final ExposureDeduplicationCache cache = evaluator == null ? null : evaluator.exposureCache();
      if (cache == null) {
        log.trace("Feature Flagging exposure suppressed: provider not initialized");
        return;
      }
      final String flag = details.getFlagKey();
      final Object serialValue = values.get(SPLIT_SERIAL_ID);
      final Integer serialId = serialValue instanceof Integer ? (Integer) serialValue : null;
      // The exposure is recorded even when it is not sent, so exposure hooks get the same
      // decisions whether or not exposures go to Datadog.
      if (!cache.recordIfChanged(
          flag, context.getTargetingKey(), allocationKey, variantKey, serialId)) {
        log.trace("Feature Flagging exposure suppressed: exposure already recorded");
      } else if (!evaluator.isExposureDeliveryEnabled()) {
        log.trace("Feature Flagging exposure suppressed: Datadog exposure delivery is disabled");
      } else {
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
      warnOnce(e);
    }
  }

  private void warnOnce(final Throwable failure) {
    if (this.warned.compareAndSet(false, true)) {
      log.warn("Could not record a feature flag exposure; later failures are not logged", failure);
    }
  }
}
