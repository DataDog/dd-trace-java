package com.datadog.openfeature;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.FeatureFlagsRuntime;
import com.datadog.openfeature.internal.FeatureFlagsRuntime.RemoteConfigurationUnavailableException;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.AgentBridges;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationWriter;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.OpenFeatureError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Datadog OpenFeature provider. */
public class Provider extends EventProvider implements Metadata {

  private static final Logger log = LoggerFactory.getLogger(Provider.class);
  static final String METADATA = "datadog-openfeature-provider";

  private static final Options DEFAULT_OPTIONS = new Options().initTimeout(30, SECONDS);
  private volatile Evaluator evaluator;
  private final Options options;
  private final AgentBridge bridge;
  private final Settings settings;
  private final AtomicReference<InitializationState> initializationState =
      new AtomicReference<>(InitializationState.NOT_STARTED);
  private final FlagEvalMetrics flagEvalMetrics;
  private final FlagEvalMetricsHook flagEvalMetricsHook;
  // Span enrichment: null unless the gate is on, so the feature has no idle overhead when off.
  private final SpanEnrichmentHook spanEnrichmentHook;
  // Precomputed hook list returned by getProviderHooks() on every evaluation. Immutable and built
  // once so gate-off evaluation allocates nothing on this hot path.
  private final List<Hook> providerHooks;

  /** Creates a provider waiting up to 30 seconds for the initial flag configuration. */
  public Provider() {
    this(DEFAULT_OPTIONS, null);
  }

  /**
   * Creates a provider.
   *
   * @param options the provider options.
   */
  public Provider(final Options options) {
    this(options, null);
  }

  Provider(final Options options, final Evaluator evaluator) {
    this(options, evaluator, null);
  }

  /**
   * @param spanEnrichmentEnabledOverride when non-null, forces the span-enrichment gate (test
   *     seam); when null, the gate is read from the settings.
   */
  Provider(
      final Options options,
      final Evaluator evaluator,
      final Boolean spanEnrichmentEnabledOverride) {
    this.options = options;
    this.evaluator = evaluator;
    this.bridge = AgentBridges.detect();
    this.settings = Settings.load(this.bridge);
    FlagEvalMetrics metrics = null;
    FlagEvalMetricsHook hook = null;
    try {
      metrics = new FlagEvalMetrics();
      hook = new FlagEvalMetricsHook(metrics);
    } catch (LinkageError | Exception e) {
      // This outer catch fires when the metrics helper itself can't load (OTel API absent).
      log.warn("Evaluation metrics unavailable — OTel API classes not on classpath", e);
    }
    this.flagEvalMetrics = metrics;
    this.flagEvalMetricsHook = hook;

    // Span enrichment is wired ONLY when the gate is on — off means no capture hook and no idle
    // per-evaluation overhead.
    final boolean spanEnrichmentEnabled =
        spanEnrichmentEnabledOverride != null
            ? spanEnrichmentEnabledOverride
            : this.settings.isSpanEnrichmentEnabled();
    this.spanEnrichmentHook =
        spanEnrichmentEnabled ? new SpanEnrichmentHook(this.bridge.spanEnricher()) : null;

    // Precompute the immutable hook list once so getProviderHooks() (called on every evaluation)
    // allocates nothing, including when the gate is off.
    final List<Hook> hooks = new ArrayList<>(4);
    if (flagEvalMetricsHook != null) {
      hooks.add(flagEvalMetricsHook);
    }
    // EVP flagevaluation hook: always registered; no-op when the pipeline is absent (killswitch
    // off or provider not initialized). The pipeline is resolved lazily on each call.
    hooks.add(new FlagEvalLoggingHook<>(this::flagEvaluationPipeline));
    if (spanEnrichmentHook != null) {
      hooks.add(spanEnrichmentHook);
    }
    if (options.isEvaluationTelemetryOptOutParameterEnabled(this.settings)) {
      for (int i = 0; i < hooks.size(); i++) {
        hooks.set(i, new EvaluationTelemetryOptOutHook(hooks.get(i)));
      }
    }
    // Exposure hook: always registered last, and independent of evaluation telemetry opt-out; no-op
    // when the evaluator is not a Datadog one or is
    // not initialized.
    hooks.add(new ExposureLoggingHook<>(this::datadogEvaluator));
    this.providerHooks =
        hooks.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(hooks);

    // Announce the span-enrichment state at startup (matches the reference implementation).
    // "enabled" only when the gate is on (the capture hook was constructed), otherwise "disabled".
    if (spanEnrichmentHook != null) {
      log.info("{} span enrichment enabled", METADATA);
    } else {
      log.info("{} span enrichment disabled", METADATA);
    }
  }

  @Override
  public void initialize(final EvaluationContext context) throws Exception {
    initializationState.set(InitializationState.INITIALIZING);
    try {
      evaluator = buildEvaluator();
      if (!evaluator.initialize(options.getTimeout(), options.getUnit(), context)) {
        if (markInitialConfigReceivedReady()) {
          return;
        }
        markInitializationError();
        throw new ProviderNotReadyError(
            "Provider timed-out while waiting for initial configuration");
      }
      if (!evaluator.hasConfiguration() || !markSuccessfulInitializationReady()) {
        markInitializationError();
        throw new ProviderNotReadyError(
            "Provider timed-out while waiting for initial configuration");
      }
    } catch (final RemoteConfigurationUnavailableException e) {
      // No RC connector means no configuration can arrive in this JVM. Complete registration
      // without waiting or switching sources. The evaluator has no runtime/configuration and
      // returns caller defaults with PROVIDER_NOT_READY, never successful flag assignments.
      // OpenFeature marks the completed registration READY; that does not imply RC is available.
      initializationState.set(InitializationState.FALLBACK);
      log.warn("{}; returning caller defaults", e.getMessage());
      log.debug("Feature Flags Remote Configuration connector unavailable", e);
    } catch (final OpenFeatureError e) {
      markInitializationError();
      throw e;
    } catch (final Throwable e) {
      markInitializationError();
      throw new FatalError(
          "Failed to initialize Datadog Feature Flags provider: " + e.getMessage(), e);
    }
  }

  void onConfigurationChange() {
    if (evaluator == null || !evaluator.hasConfiguration()) {
      onConfigurationUnavailable();
      return;
    }

    final InitializationState state = initializationState.get();
    if (state == InitializationState.INITIALIZING) {
      initializationState.compareAndSet(
          InitializationState.INITIALIZING, InitializationState.INITIAL_CONFIG_RECEIVED);
      return;
    }
    if (state == InitializationState.INITIAL_CONFIG_RECEIVED) {
      return;
    }
    if (state == InitializationState.ERROR
        && initializationState.compareAndSet(
            InitializationState.ERROR, InitializationState.READY)) {
      emit(
          ProviderEvent.PROVIDER_READY,
          ProviderEventDetails.builder().message("Provider ready").build());
      return;
    }
    if (initializationState.get() != InitializationState.READY) {
      return;
    }
    emit(
        ProviderEvent.PROVIDER_CONFIGURATION_CHANGED,
        ProviderEventDetails.builder().message("New configuration received").build());
  }

  private void onConfigurationUnavailable() {
    if (initializationState.compareAndSet(
        InitializationState.INITIAL_CONFIG_RECEIVED, InitializationState.ERROR)) {
      return;
    }
    if (!initializationState.compareAndSet(InitializationState.READY, InitializationState.ERROR)) {
      return;
    }
    emit(
        ProviderEvent.PROVIDER_ERROR,
        ProviderEventDetails.builder()
            .message("Configuration unavailable")
            .errorCode(ErrorCode.PROVIDER_NOT_READY)
            .build());
  }

  private boolean markInitialConfigReceivedReady() {
    return initializationState.get() == InitializationState.READY
        || initializationState.compareAndSet(
            InitializationState.INITIAL_CONFIG_RECEIVED, InitializationState.READY);
  }

  private boolean markSuccessfulInitializationReady() {
    return markInitialConfigReceivedReady()
        || initializationState.compareAndSet(
            InitializationState.INITIALIZING, InitializationState.READY);
  }

  private void markInitializationError() {
    InitializationState state = initializationState.get();
    while (state != InitializationState.READY && state != InitializationState.ERROR) {
      if (initializationState.compareAndSet(state, InitializationState.ERROR)) {
        return;
      }
      state = initializationState.get();
    }
  }

  private Evaluator buildEvaluator() {
    if (evaluator != null) {
      return evaluator;
    }
    return new DDEvaluator(this::onConfigurationChange, this.bridge, this.settings);
  }

  private FlagEvaluationWriter flagEvaluationPipeline() {
    final DDEvaluator current = datadogEvaluator();
    if (current == null) {
      return null;
    }
    final FeatureFlagsRuntime runtime = current.runtime();
    return runtime == null ? null : runtime.evaluations();
  }

  private DDEvaluator datadogEvaluator() {
    final Evaluator current = this.evaluator;
    return current instanceof DDEvaluator ? (DDEvaluator) current : null;
  }

  @Override
  public List<Hook> getProviderHooks() {
    return providerHooks;
  }

  @Override
  public void shutdown() {
    if (flagEvalMetrics != null) {
      flagEvalMetrics.shutdown();
    }
    // Span enrichment needs no provider-close cleanup here: the capture hook holds no tracer state.
    if (evaluator != null) {
      evaluator.shutdown();
    }
  }

  // Visible for tests: expose whether span enrichment is wired (gate-on) without leaking the impl.
  SpanEnrichmentHook spanEnrichmentHook() {
    return spanEnrichmentHook;
  }

  @Override
  public Metadata getMetadata() {
    return this;
  }

  @Override
  public String getName() {
    return METADATA;
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      final String key, final Boolean defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Boolean.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      final String key, final String defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(String.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      final String key, final Integer defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Integer.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      final String key, final Double defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Double.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      final String key, final Value defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Value.class, key, defaultValue, ctx);
  }

  private enum InitializationState {
    NOT_STARTED,
    INITIALIZING,
    INITIAL_CONFIG_RECEIVED,
    READY,
    ERROR,
    FALLBACK
  }

  /** The provider options. */
  public static class Options {

    private long timeout = 30;
    private TimeUnit unit = SECONDS;
    private Boolean evaluationTelemetryOptOutParameterEnabled;

    /**
     * Enables per-evaluation telemetry opt-out through the {@code dd_evaluation_telemetry_opt_out}
     * context attribute. Overrides {@code
     * DD_FEATURE_FLAGS_EVALUATION_TELEMETRY_PER_SUBJECT_OPT_OUT_ENABLED}, including when false.
     * Without an explicit option, the provider reads configuration at construction. Disabled by
     * default.
     *
     * <p>Only boolean {@code true} suppresses new Datadog evaluation events, evaluation metrics,
     * and automatic flag span enrichment. Missing or other values preserve telemetry. This does not
     * change flag results, exposure logging, application hooks, or previously recorded data.
     *
     * <p>The attribute remains available for targeting and exposure reporting. Existing privacy and
     * reporting settings apply.
     *
     * @param enabled whether to honor the attribute; configure before constructing the provider.
     * @return these options.
     */
    public Options enableEvaluationTelemetryOptOutParameter(final boolean enabled) {
      this.evaluationTelemetryOptOutParameterEnabled = enabled;
      return this;
    }

    private boolean isEvaluationTelemetryOptOutParameterEnabled(final Settings settings) {
      if (this.evaluationTelemetryOptOutParameterEnabled != null) {
        return this.evaluationTelemetryOptOutParameterEnabled;
      }
      return settings.isEvaluationTelemetryPerSubjectOptOutEnabled();
    }

    /**
     * Sets how long provider initialization waits for the initial flag configuration.
     *
     * @param timeout the timeout.
     * @param unit the timeout unit.
     * @return these options.
     */
    public Options initTimeout(final long timeout, final TimeUnit unit) {
      this.timeout = timeout;
      this.unit = unit;
      return this;
    }

    public long getTimeout() {
      return timeout;
    }

    public TimeUnit getUnit() {
      return unit;
    }
  }
}
