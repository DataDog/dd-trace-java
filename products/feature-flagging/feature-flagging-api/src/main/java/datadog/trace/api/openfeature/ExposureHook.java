package datadog.trace.api.openfeature;

import static java.util.Objects.requireNonNull;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An OpenFeature hook that hands each completed flag evaluation to a callback, with the Datadog
 * exposure decision, so an application can record exposures in its own data store.
 *
 * <p>This hook is optional. The provider puts the same data in the flag metadata, so any
 * OpenFeature hook can read it (see "Flag metadata" in the README). This class reads it for you.
 *
 * <p>Register it with {@code OpenFeatureAPI.getInstance().addHooks(new ExposureHook(callback))} or
 * on a client. The callback runs for every evaluation, including errors and repeats. Use {@link
 * Evaluation#shouldSend()} to keep the new or changed exposures. A changed serial id counts as a
 * change only with {@code dd-java-agent} 1.67.0 or later; with an older agent, only a changed
 * allocation or variant does.
 *
 * <p>When the provider's own hooks run and {@code
 * DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED} is on, these are exactly the exposures that
 * Datadog sends. With that setting off, Datadog receives no exposures, and {@code shouldSend()}
 * gives the same results. The provider's hooks run when the application evaluates through an
 * OpenFeature client. Inside a {@code MultiProvider} they run only with OpenFeature SDK 1.23.0 or
 * later. With an older SDK, Datadog receives no exposures from a provider inside a {@code
 * MultiProvider}, and {@code shouldSend()} is true for every exposure.
 *
 * <p>The callback runs on the evaluation thread: hand the data to a background queue instead of
 * writing it from the callback. An exception from the callback does not affect the evaluation. The
 * first one is logged at warn level, later ones at debug level.
 */
public final class ExposureHook implements Hook<Object> {

  private static final Logger log = LoggerFactory.getLogger(ExposureHook.class);

  private final Consumer<Evaluation> callback;
  private final AtomicBoolean warned = new AtomicBoolean();

  public ExposureHook(final Consumer<Evaluation> callback) {
    this.callback = requireNonNull(callback, "callback");
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    if (details == null) {
      return;
    }
    try {
      callback.accept(new Evaluation(context.getCtx(), details));
    } catch (final LinkageError | RuntimeException e) {
      if (warned.compareAndSet(false, true)) {
        log.warn("Exposure callback failed; later failures are logged at debug level", e);
      } else {
        log.debug("Exposure callback failed", e);
      }
    }
  }

  /** A completed evaluation and its Datadog exposure decision. */
  public static final class Evaluation {
    private final EvaluationContext context;
    private final FlagEvaluationDetails<Object> details;

    Evaluation(final EvaluationContext context, final FlagEvaluationDetails<Object> details) {
      this.context = context;
      this.details = details;
    }

    /** The evaluation context, including the targeting key. */
    public EvaluationContext getContext() {
      return context;
    }

    /** The flag key, value, variant, error and flag metadata of the evaluation. */
    public FlagEvaluationDetails<Object> getDetails() {
      return details;
    }

    /**
     * True when the evaluation succeeded and selected an allocation that logs exposures, whether or
     * not this subject was already exposed.
     */
    public boolean isExposure() {
      final Map<String, Object> values = metadata();
      return details.getErrorCode() == null
          && details.getVariant() != null
          && values.get("allocationKey") instanceof String
          && Boolean.TRUE.equals(values.get(DDEvaluator.METADATA_DO_LOG));
    }

    /**
     * True when this exposure repeats one already recorded for this subject: same allocation,
     * variant and serial id. False for a new or changed exposure, and for an evaluation that is not
     * an exposure.
     */
    public boolean isCacheHit() {
      return isExposure() && Boolean.TRUE.equals(cacheHitStamp());
    }

    /**
     * True for a new or changed exposure: the decision the provider uses for Datadog. The class
     * Javadoc says when that decision reaches Datadog.
     */
    public boolean shouldSend() {
      return isExposure() && Boolean.FALSE.equals(cacheHitStamp());
    }

    private Object cacheHitStamp() {
      return metadata().get(DDEvaluator.METADATA_EXPOSURE_CACHE_HIT);
    }

    // The ImmutableMetadata getters build a debug message for every absent key.
    private Map<String, Object> metadata() {
      final ImmutableMetadata metadata = details.getFlagMetadata();
      return metadata == null
          ? Collections.<String, Object>emptyMap()
          : metadata.asUnmodifiableMap();
    }

    /**
     * The features of the selected split, such as {@code holdout.key} and {@code
     * holdout.assignment_group}. Values are a String, Double or Boolean. Empty when the split has
     * none.
     */
    public Map<String, Object> getFeatures() {
      return SplitFeatureMetadata.featuresWithPrefix(
          details.getFlagMetadata(), DDEvaluator.METADATA_FEATURE_PREFIX);
    }
  }
}
