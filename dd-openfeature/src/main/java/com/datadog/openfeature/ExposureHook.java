package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An OpenFeature hook that hands each completed flag evaluation to a callback, with the Datadog
 * exposure decision, so an application can record exposures in its own data store.
 *
 * <p>Other hooks can read the allocation key and the features from the flag metadata (see "Flag
 * metadata" in the README), but the exposure decision is available only from this hook.
 *
 * <p>Register it with {@code OpenFeatureAPI.getInstance().addHooks(new ExposureHook(callback))} or
 * on a client. The callback runs for every evaluation, including errors and repeats. A successful
 * dependent-flag evaluation also invokes it for each selected prerequisite exposure. Use {@link
 * Evaluation#shouldSend()} to keep the new or changed exposures.
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
  private static final AtomicLong LIFECYCLE_IDS = new AtomicLong();
  private static final ThreadLocal<List<Registration>> ACTIVE_REGISTRATIONS = new ThreadLocal<>();

  private final Consumer<Evaluation> callback;
  private final AtomicBoolean warned = new AtomicBoolean();
  private final String lifecycleKey =
      "datadog.exposureHookLifecycle." + LIFECYCLE_IDS.incrementAndGet();

  /**
   * Creates an exposure hook.
   *
   * @param callback the callback receiving each completed evaluation.
   * @throws NullPointerException if the callback is {@code null}.
   */
  public ExposureHook(final Consumer<Evaluation> callback) {
    this.callback = requireNonNull(callback, "callback");
  }

  @Override
  public Optional<EvaluationContext> before(
      final HookContext<Object> context, final Map<String, Object> hints) {
    if (context == null || context.getHookData() == null) {
      return Optional.empty();
    }
    final Registration registration = new Registration(this, context);
    context.getHookData().set(this.lifecycleKey, registration);
    List<Registration> registrations = ACTIVE_REGISTRATIONS.get();
    if (registrations == null) {
      registrations = new ArrayList<>();
      ACTIVE_REGISTRATIONS.set(registrations);
    }
    registrations.add(registration);
    return Optional.empty();
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    final Registration registration = registration(context);
    if (registration == null) {
      return;
    }
    try {
      if (details != null) {
        publish(new Evaluation(context.getCtx(), details));
      }
    } finally {
      unregister(registration);
    }
  }

  static void bindProviderScope(final Object scope, final HookContext<?> context) {
    if (scope == null || context == null) {
      return;
    }
    final List<Registration> registrations = ACTIVE_REGISTRATIONS.get();
    if (registrations == null || registrations.isEmpty()) {
      return;
    }
    final Set<ExposureHook> bound =
        Collections.newSetFromMap(new IdentityHashMap<ExposureHook, Boolean>());
    for (int i = registrations.size() - 1; i >= 0; i--) {
      final Registration registration = registrations.get(i);
      if (!bound.add(registration.hook)) {
        continue;
      }
      if (registration.active
          && registration.providerScope == null
          && registration.matches(context)) {
        registration.providerScope = scope;
      }
    }
  }

  static void publishDependencies(
      final Object scope,
      final HookContext<?> context,
      final List<FlagEvaluationDetails<Object>> details) {
    if (scope == null || context == null || details == null || details.isEmpty()) {
      return;
    }
    final List<Registration> registrations = ACTIVE_REGISTRATIONS.get();
    if (registrations == null || registrations.isEmpty()) {
      return;
    }
    final List<Registration> recipients = new ArrayList<>();
    for (final Registration registration : registrations) {
      if (registration.active && registration.providerScope == scope) {
        recipients.add(registration);
      }
    }
    for (final Registration registration : recipients) {
      for (final FlagEvaluationDetails<Object> dependency : details) {
        registration.hook.publish(new Evaluation(context.getCtx(), dependency));
      }
    }
  }

  private Registration registration(final HookContext<?> context) {
    if (context == null || context.getHookData() == null) {
      return null;
    }
    final Object value = context.getHookData().get(this.lifecycleKey);
    if (!(value instanceof Registration)) {
      return null;
    }
    final Registration registration = (Registration) value;
    return registration.active && registration.hook == this ? registration : null;
  }

  private static void unregister(final Registration registration) {
    registration.active = false;
    final List<Registration> registrations = ACTIVE_REGISTRATIONS.get();
    if (registrations == null) {
      return;
    }
    for (int i = registrations.size() - 1; i >= 0; i--) {
      if (registrations.get(i) == registration) {
        registrations.remove(i);
        break;
      }
    }
    if (registrations.isEmpty()) {
      ACTIVE_REGISTRATIONS.remove();
    }
  }

  private static final class Registration {
    private final ExposureHook hook;
    private final String flagKey;
    private final EvaluationContext evaluationContext;
    private Object providerScope;
    private boolean active = true;

    private Registration(final ExposureHook hook, final HookContext<?> context) {
      this.hook = hook;
      this.flagKey = context.getFlagKey();
      this.evaluationContext = context.getCtx();
    }

    private boolean matches(final HookContext<?> context) {
      return java.util.Objects.equals(this.flagKey, context.getFlagKey())
          && this.evaluationContext == context.getCtx();
    }
  }

  private void publish(final Evaluation evaluation) {
    try {
      this.callback.accept(evaluation);
    } catch (final LinkageError | RuntimeException e) {
      if (this.warned.compareAndSet(false, true)) {
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

    /**
     * Gets the evaluation context.
     *
     * @return the evaluation context, including the targeting key.
     */
    public EvaluationContext getContext() {
      return this.context;
    }

    /**
     * Gets the evaluation details.
     *
     * @return the flag key, value, variant, error and flag metadata of the evaluation.
     */
    public FlagEvaluationDetails<Object> getDetails() {
      return this.details;
    }

    /**
     * Gets the evaluated flag key.
     *
     * @return the flag key.
     */
    public String getFlagKey() {
      return this.details.getFlagKey();
    }

    /**
     * Gets the selected variant.
     *
     * @return the variant key, or {@code null} when the evaluation did not select one.
     */
    public String getVariant() {
      return this.details.getVariant();
    }

    /**
     * Gets the key of the selected allocation.
     *
     * @return the allocation key, or {@code null} when the provider did not select one, for example
     *     when it failed or returned the default value.
     */
    public String getAllocationKey() {
      final Object allocationKey = metadata().get(ALLOCATION_KEY);
      return allocationKey instanceof String ? (String) allocationKey : null;
    }

    /**
     * Gets the targeting key of the evaluation context.
     *
     * @return the targeting key, or {@code null} when the context has none.
     */
    public String getTargetingKey() {
      return this.context == null ? null : this.context.getTargetingKey();
    }

    /**
     * Tells whether the evaluation is an exposure, whether or not this subject was already exposed.
     *
     * @return {@code true} when the evaluation succeeded and selected an allocation that logs
     *     exposures.
     */
    public boolean isExposure() {
      final Map<String, Object> values = metadata();
      return this.details.getErrorCode() == null
          && this.details.getVariant() != null
          && values.get(ALLOCATION_KEY) instanceof String
          && Boolean.TRUE.equals(values.get(DO_LOG));
    }

    /**
     * Tells whether this exposure repeats one already recorded for this subject: same allocation,
     * variant and serial id.
     *
     * @return {@code true} for a repeated exposure, {@code false} for a new or changed exposure and
     *     for an evaluation that is not an exposure.
     */
    public boolean isCacheHit() {
      return isExposure() && Boolean.TRUE.equals(cacheHitStamp());
    }

    /**
     * Tells whether this is a new or changed exposure: the decision the provider uses for Datadog.
     * The class Javadoc says when that decision reaches Datadog.
     *
     * @return {@code true} for a new or changed exposure.
     */
    public boolean shouldSend() {
      return isExposure() && Boolean.FALSE.equals(cacheHitStamp());
    }

    /**
     * Gets the features of the selected split, such as {@code holdout.key} and {@code
     * holdout.assignment_group}. Values are a String, Double or Boolean.
     *
     * @return the unmodifiable features, empty when the split has none.
     */
    public Map<String, Object> getFeatures() {
      return SplitFeatureMetadata.featuresWithPrefix(
          this.details.getFlagMetadata(), FEATURE_PREFIX);
    }

    private Object cacheHitStamp() {
      return metadata().get(EXPOSURE_CACHE_HIT);
    }

    // The ImmutableMetadata getters build a debug message for every absent key.
    private Map<String, Object> metadata() {
      final ImmutableMetadata metadata = this.details.getFlagMetadata();
      return metadata == null ? emptyMap() : metadata.asUnmodifiableMap();
    }
  }
}
