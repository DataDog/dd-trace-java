package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal provider hook that hands exposures to the existing asynchronous Datadog writer. */
final class ExposureLoggingHook implements Hook<Object> {
  private static final Logger log = LoggerFactory.getLogger(ExposureLoggingHook.class);
  static final AtomicBoolean USE_LEGACY_EXPOSURE_API =
      new AtomicBoolean(!exposureSerialIdSupported(ExposureEvent.class));

  // Resolve once per provider, like SpanEnrichmentGate: dd-openfeature is distributed separately
  // and must also work with agents that have no accessor for this setting on Config.
  static boolean isEnabled() {
    try {
      return ConfigProvider.getInstance()
          .getBoolean(FeatureFlaggingConfig.FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED, true);
    } catch (final LinkageError | RuntimeException e) {
      return true;
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    try {
      final ExposureHook.Evaluation evaluation =
          new ExposureHook.Evaluation(context.getCtx(), details);
      if (!evaluation.shouldSend()) {
        return;
      }
      final ImmutableMetadata metadata = details.getFlagMetadata();
      final Long evaluationTime = metadata.getLong("__dd_eval_timestamp_ms");
      final long timestamp = evaluationTime != null ? evaluationTime : System.currentTimeMillis();
      final Allocation allocation = new Allocation(metadata.getString("allocationKey"));
      final Flag flag = new Flag(details.getFlagKey());
      final Variant variant = new Variant(details.getVariant());
      final Subject subject =
          new Subject(
              context.getCtx().getTargetingKey(), DDEvaluator.flattenContext(context.getCtx()));
      final ExposureEvent event =
          USE_LEGACY_EXPOSURE_API.get()
              ? new ExposureEvent(timestamp, allocation, flag, variant, subject)
              : new ExposureEvent(
                  timestamp,
                  allocation,
                  flag,
                  variant,
                  subject,
                  metadata.getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
      FeatureFlaggingGateway.dispatch(event);
    } catch (final LinkageError | RuntimeException e) {
      log.debug("Exposure logging failed", e);
    }
  }

  static boolean exposureSerialIdSupported(final Class<?> eventClass) {
    try {
      eventClass.getConstructor(
          long.class,
          datadog.trace.api.featureflag.exposure.Allocation.class,
          datadog.trace.api.featureflag.exposure.Flag.class,
          datadog.trace.api.featureflag.exposure.Variant.class,
          Subject.class,
          Integer.class);
      return true;
    } catch (final NoSuchMethodException | LinkageError | RuntimeException e) {
      log.warn(
          "Feature flag exposure serial ID reporting is unavailable with the installed "
              + "Datadog Java agent. Exposures are still reported, without the serial id, and "
              + "span enrichment is unaffected. Upgrade dd-java-agent to enable holdout "
              + "attribution on exposures.");
      log.debug("Unable to access the exposure serial ID constructor", e);
      return false;
    }
  }
}
