package datadog.trace.api.openfeature;

import static java.util.Objects.requireNonNull;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The exposure hook, under its former package name.
 *
 * @deprecated Use {@link com.datadog.openfeature.ExposureHook} instead.
 */
@Deprecated
public final class ExposureHook implements Hook<Object> {
  private final com.datadog.openfeature.ExposureHook delegate;

  /**
   * Creates an exposure hook.
   *
   * @param callback the callback receiving each completed evaluation.
   * @throws NullPointerException if the callback is {@code null}.
   */
  public ExposureHook(final Consumer<Evaluation> callback) {
    requireNonNull(callback, "callback");
    this.delegate =
        new com.datadog.openfeature.ExposureHook(
            evaluation -> callback.accept(new Evaluation(evaluation)));
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    this.delegate.finallyAfter(context, details, hints);
  }

  /**
   * A completed evaluation and its Datadog exposure decision, under its former package name.
   *
   * @deprecated Use {@link com.datadog.openfeature.ExposureHook.Evaluation} instead.
   */
  @Deprecated
  public static final class Evaluation {
    private final com.datadog.openfeature.ExposureHook.Evaluation delegate;

    Evaluation(final com.datadog.openfeature.ExposureHook.Evaluation delegate) {
      this.delegate = delegate;
    }

    /**
     * Gets the evaluation context.
     *
     * @return the evaluation context, including the targeting key.
     */
    public EvaluationContext getContext() {
      return this.delegate.getContext();
    }

    /**
     * Gets the evaluation details.
     *
     * @return the flag key, value, variant, error and flag metadata of the evaluation.
     */
    public FlagEvaluationDetails<Object> getDetails() {
      return this.delegate.getDetails();
    }

    /**
     * Tells whether the evaluation is an exposure, whether or not this subject was already exposed.
     *
     * @return {@code true} when the evaluation succeeded and selected an allocation that logs
     *     exposures.
     */
    public boolean isExposure() {
      return this.delegate.isExposure();
    }

    /**
     * Tells whether this exposure repeats one already recorded for this subject.
     *
     * @return {@code true} for a repeated exposure, {@code false} for a new or changed exposure and
     *     for an evaluation that is not an exposure.
     */
    public boolean isCacheHit() {
      return this.delegate.isCacheHit();
    }

    /**
     * Tells whether this is a new or changed exposure: the decision the provider uses for Datadog.
     *
     * @return {@code true} for a new or changed exposure.
     */
    public boolean shouldSend() {
      return this.delegate.shouldSend();
    }

    /**
     * Gets the features of the selected split.
     *
     * @return the unmodifiable features, empty when the split has none.
     */
    public Map<String, Object> getFeatures() {
      return this.delegate.getFeatures();
    }
  }
}
