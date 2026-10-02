package datadog.trace.api.openfeature;

import static java.util.Objects.requireNonNull;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customer-registered OpenFeature evaluation hook with Datadog exposure advisories.
 *
 * <p>Register with {@code client.addHooks(new ExposureHook(evaluation -> ...))}. The callback runs
 * for every evaluation, including errors and cache hits, independently of Datadog exposure logging.
 * Use {@link Evaluation#isExposure()} and {@link Evaluation#getCacheHit()} to choose what to send.
 * These are advisory: the SDK cannot know whether an evaluated value was actually used.
 *
 * <p>The callback runs synchronously on the evaluation thread. Keep it short and copy any context
 * fields needed by asynchronous work. Callback exceptions are isolated from flag evaluation.
 */
public final class ExposureHook implements Hook<Object> {
  public static final String CACHE_HIT_METADATA_KEY = "__dd_exposure_cache_hit";
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

  /** The completed evaluation and advisories from its provider resolution. */
  public static final class Evaluation {
    private final EvaluationContext context;
    private final FlagEvaluationDetails<Object> details;

    Evaluation(final EvaluationContext context, final FlagEvaluationDetails<Object> details) {
      this.context = context;
      this.details = details;
    }

    public EvaluationContext getContext() {
      return context;
    }

    /** Includes the value, variant, error, allocationKey and optional __dd_split_serial_id. */
    public FlagEvaluationDetails<Object> getDetails() {
      return details;
    }

    /** True when a successful evaluation selected an allocation configured to log exposures. */
    public boolean isExposure() {
      final ImmutableMetadata metadata = details.getFlagMetadata();
      return details.getErrorCode() == null
          && details.getVariant() != null
          && metadata != null
          && metadata.getString("allocationKey") != null
          && Boolean.TRUE.equals(metadata.getBoolean(DDEvaluator.METADATA_DO_LOG));
    }

    /**
     * True for an unchanged assignment in the provider's bounded observation cache; false for a new
     * or changed assignment; null when not applicable or unavailable. This says nothing about
     * delivery to Datadog or any customer destination.
     */
    public Boolean getCacheHit() {
      return isExposure() ? details.getFlagMetadata().getBoolean(CACHE_HIT_METADATA_KEY) : null;
    }

    /** A convenience policy for customers who want only exposure candidates with a cache miss. */
    public boolean shouldSend() {
      return isExposure() && Boolean.FALSE.equals(getCacheHit());
    }
  }
}
