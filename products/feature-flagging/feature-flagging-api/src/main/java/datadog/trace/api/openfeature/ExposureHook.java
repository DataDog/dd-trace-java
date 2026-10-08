package datadog.trace.api.openfeature;

import static java.util.Objects.requireNonNull;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An OpenFeature hook that hands each completed flag evaluation to a callback, with the Datadog
 * exposure decision, so an application can record exposures in its own data store.
 *
 * <p>Register it with {@code OpenFeatureAPI.getInstance().addHooks(new ExposureHook(callback))} or
 * on a client. The callback runs for every evaluation, including errors and repeats. Use {@link
 * Evaluation#shouldSend()} to keep exactly the exposures that Datadog sends. That decision is the
 * same whether or not {@code DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED} is set.
 *
 * <p>The callback runs on the evaluation thread: hand the data to a background queue instead of
 * writing it from the callback. An exception from the callback is logged and does not affect the
 * evaluation.
 */
public final class ExposureHook implements Hook<Object> {

  private static final Logger log = LoggerFactory.getLogger(ExposureHook.class);

  private final Consumer<Evaluation> callback;

  public ExposureHook(final Consumer<Evaluation> callback) {
    this.callback = requireNonNull(callback, "callback");
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    try {
      callback.accept(new Evaluation(context.getCtx(), details));
    } catch (final LinkageError | RuntimeException e) {
      log.debug("Exposure callback failed", e);
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
      final ImmutableMetadata metadata = details.getFlagMetadata();
      return details.getErrorCode() == null
          && details.getVariant() != null
          && metadata != null
          && metadata.getString("allocationKey") != null
          && Boolean.TRUE.equals(metadata.getBoolean(DDEvaluator.METADATA_DO_LOG));
    }

    /**
     * True when this exposure repeats one already recorded for this subject: same allocation,
     * variant and serial id. False for a new or changed exposure, and for an evaluation that is not
     * an exposure.
     */
    public boolean isCacheHit() {
      return isExposure() && Boolean.TRUE.equals(cacheHitStamp());
    }

    /** True for exactly the exposures that Datadog sends: new or changed exposures. */
    public boolean shouldSend() {
      return isExposure() && Boolean.FALSE.equals(cacheHitStamp());
    }

    private Boolean cacheHitStamp() {
      return details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_EXPOSURE_CACHE_HIT);
    }

    /**
     * The features of the selected split, such as {@code holdout.key} and {@code
     * holdout.assignment_group}. Values are a String, Double or Boolean. Empty when the split has
     * none.
     */
    public Map<String, Object> getFeatures() {
      final ImmutableMetadata metadata = details.getFlagMetadata();
      if (metadata == null) {
        return Collections.emptyMap();
      }
      Map<String, Object> features = null;
      for (final Map.Entry<String, Object> entry : metadata.asUnmodifiableMap().entrySet()) {
        if (entry.getKey().startsWith(DDEvaluator.METADATA_FEATURE_PREFIX)) {
          if (features == null) {
            features = new HashMap<>();
          }
          features.put(
              entry.getKey().substring(DDEvaluator.METADATA_FEATURE_PREFIX.length()),
              entry.getValue());
        }
      }
      return features == null
          ? Collections.<String, Object>emptyMap()
          : Collections.unmodifiableMap(features);
    }
  }
}
