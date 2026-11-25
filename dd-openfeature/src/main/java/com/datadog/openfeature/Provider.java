package com.datadog.openfeature;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.FeatureFlagsRuntime;
import com.datadog.openfeature.internal.FeatureFlagsRuntime.MissingApiKeyException;
import com.datadog.openfeature.internal.FeatureFlagsRuntime.RemoteConfigurationUnavailableException;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.AgentBridges;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.delivery.FlagSelectionRequest;
import com.datadog.openfeature.internal.evaluation.ExperimentationOptOut;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
  private final ExperimentationOptOutMode experimentationOptOutMode;
  private final FlagSelectionRequest flagSelection;
  private final AtomicReference<InitializationState> initializationState =
      new AtomicReference<>(InitializationState.NOT_STARTED);
  private final boolean evaluationTelemetryEnabled;
  private final boolean exposuresEnabled;
  private final boolean spanEnrichmentEnabled;
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
    this.experimentationOptOutMode =
        this.settings.isEnabled()
            ? ExperimentationOptOut.resolve(options.experimentationOptOutMode, this.settings)
            : ExperimentationOptOutMode.DISABLED;
    final FlagSelection selection = options.flagSelection;
    this.flagSelection =
        selection == null
            ? FlagSelectionRequest.NONE
            : FlagSelectionRequest.of(selection.tags, selection.keyPatterns);
    this.evaluationTelemetryEnabled = options.evaluationTelemetryEnabled;
    this.exposuresEnabled = options.exposuresEnabled;
    FlagEvalMetrics metrics = null;
    FlagEvalMetricsHook hook = null;
    if (this.evaluationTelemetryEnabled) {
      try {
        metrics = new FlagEvalMetrics();
        hook = new FlagEvalMetricsHook(metrics);
      } catch (LinkageError | Exception e) {
        // This outer catch fires when the metrics helper itself can't load (OTel API absent).
        log.warn("Evaluation metrics unavailable — OTel API classes not on classpath", e);
      }
    }
    this.flagEvalMetrics = metrics;
    this.flagEvalMetricsHook = hook;

    // Span enrichment is wired ONLY when the gate is on and evaluation telemetry is enabled — off
    // means no capture hook and no idle per-evaluation overhead.
    this.spanEnrichmentEnabled =
        this.evaluationTelemetryEnabled
            && (spanEnrichmentEnabledOverride != null
                ? spanEnrichmentEnabledOverride
                : this.settings.isSpanEnrichmentEnabled());
    this.spanEnrichmentHook =
        this.spanEnrichmentEnabled ? new SpanEnrichmentHook(this.bridge.spanEnricher()) : null;

    // Precompute the immutable hook list once so getProviderHooks() (called on every evaluation)
    // allocates nothing, including when the gate is off.
    // The hooks all act at the finally stage, so they run behind one composite hook: the SDK sets
    // up
    // a hook context for each registered hook on every evaluation.
    final CompositeProviderHook.Builder hooks = new CompositeProviderHook.Builder();
    if (flagEvalMetricsHook != null) {
      hooks.addTelemetry(flagEvalMetricsHook);
    }
    // EVP flagevaluation hook: registered when evaluation telemetry is enabled; no-op when the
    // pipeline is absent (killswitch off or provider not initialized). The pipeline is resolved
    // lazily on each call.
    if (this.evaluationTelemetryEnabled) {
      hooks.addTelemetry(new FlagEvalLoggingHook<>(this::flagEvaluationPipeline));
    }
    if (spanEnrichmentHook != null) {
      hooks.addTelemetry(spanEnrichmentHook);
    }
    hooks.optOutEnabled(options.isEvaluationTelemetryOptOutParameterEnabled(this.settings));
    // Local debug diagnostics are not telemetry, so the opt-out does not apply to them.
    if (this.settings.isTraceDebug()) {
      if (DebugLoggingHook.isLoggerEnabled()) {
        hooks.add(new DebugLoggingHook<>());
        DebugLoggingHook.announce();
      } else {
        // DD_TRACE_DEBUG cannot raise the application's logger levels, so name the second gate.
        log.warn(
            "DD_TRACE_DEBUG is enabled, but DEBUG is disabled for {} in the application's logging"
                + " configuration; evaluation debug diagnostics are off. Enable DEBUG for that"
                + " category before constructing the Provider.",
            DebugLoggingHook.class.getName());
      }
    }
    // Exposure hook: registered last when exposures are enabled, and independent of evaluation
    // telemetry; no-op when the evaluator is not a Datadog one or is not initialized. With
    // exposures disabled, the evaluator also reports no evaluation as an exposure, so neither the
    // shared deduplication cache nor application exposure hooks treat this provider's evaluations
    // as exposures.
    if (this.exposuresEnabled) {
      hooks.add(new ExposureLoggingHook<>(this::datadogEvaluator));
    }
    this.providerHooks = hooks.build();

    // Announce the span-enrichment state at startup (matches the reference implementation).
    // "enabled" only when the gate is on (the capture hook was constructed), otherwise "disabled".
    if (spanEnrichmentHook != null) {
      log.info("{} span enrichment enabled", METADATA);
    } else {
      log.info("{} span enrichment disabled", METADATA);
    }
    log.info(
        "{} experimentation opt-out mode: {}",
        METADATA,
        this.experimentationOptOutMode.name().toLowerCase(Locale.ROOT));
    if (!this.evaluationTelemetryEnabled || !this.exposuresEnabled) {
      log.info(
          "{} evaluation telemetry {}, exposures {}",
          METADATA,
          this.evaluationTelemetryEnabled ? "enabled" : "disabled",
          this.exposuresEnabled ? "enabled" : "disabled");
    }
  }

  @Override
  public void initialize(final EvaluationContext context) throws Exception {
    initializationState.set(InitializationState.INITIALIZING);
    debug(
        "Feature Flagging provider initializing: timeout={} {}",
        options.getTimeout(),
        options.getUnit());
    try {
      evaluator = buildEvaluator();
      if (!evaluator.initialize(options.getTimeout(), options.getUnit(), context)) {
        if (markInitialConfigReceivedReady()) {
          debug("Feature Flagging provider ready: configuration received during initialization");
          return;
        }
        markInitializationError();
        debug("Feature Flagging provider initialization timed out waiting for configuration");
        throw new ProviderNotReadyError(
            "Provider timed-out while waiting for initial configuration");
      }
      if (!evaluator.hasConfiguration() || !markSuccessfulInitializationReady()) {
        markInitializationError();
        debug("Feature Flagging provider initialization timed out waiting for configuration");
        throw new ProviderNotReadyError(
            "Provider timed-out while waiting for initial configuration");
      }
      debug("Feature Flagging provider ready");
    } catch (final MissingApiKeyException e) {
      // No configuration can arrive, so fail now instead of waiting for the timeout.
      markInitializationError();
      error("{}; returning caller defaults", e.getMessage());
      throw new ProviderNotReadyError(e.getMessage());
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
      // Only the type: the message of a configuration error can echo a URL with credentials.
      debug("Feature Flagging provider initialization failed: {}", e.getClass().getName());
      throw new FatalError(
          "Failed to initialize Datadog Feature Flags provider: " + e.getMessage(), e);
    }
  }

  /**
   * Logs a lifecycle diagnostic. A failing logging backend must not turn a state change into a
   * failure, such as a successful initialization into an error.
   */
  private static void debug(final String message, final Object... arguments) {
    try {
      log.debug(message, arguments);
    } catch (final RuntimeException | LinkageError ignored) {
      // Nothing more can be done.
    }
  }

  /** Logs a lifecycle error. Like {@link #debug}, a failing logging backend changes no state. */
  private static void error(final String message, final Object... arguments) {
    try {
      log.error(message, arguments);
    } catch (final RuntimeException | LinkageError ignored) {
      // Nothing more can be done.
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
      debug("Feature Flagging provider recovered: configuration received after an error");
      emit(
          ProviderEvent.PROVIDER_READY,
          ProviderEventDetails.builder().message("Provider ready").build());
      return;
    }
    if (initializationState.get() != InitializationState.READY) {
      return;
    }
    debug("Feature Flagging provider configuration changed");
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
    debug("Feature Flagging provider configuration unavailable; evaluations return defaults");
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

  /**
   * Returns the injected evaluator, or a Datadog evaluator built with the options this provider
   * captured. Package-private for tests.
   */
  Evaluator buildEvaluator() {
    if (evaluator != null) {
      return evaluator;
    }
    return new DDEvaluator(
        this::onConfigurationChange,
        this.bridge,
        this.settings,
        this.experimentationOptOutMode,
        this.flagSelection,
        this.spanEnrichmentEnabled,
        this.exposuresEnabled);
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

  /** The hooks behind the composite provider hook, in the order they run. For tests. */
  List<Hook<Object>> registeredHooks() {
    return this.providerHooks.isEmpty()
        ? Collections.<Hook<Object>>emptyList()
        : ((CompositeProviderHook) this.providerHooks.get(0)).hooks();
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
    // Logged last: a diagnostic must not get in the way of the cleanup.
    log.debug("Feature Flagging provider shut down");
  }

  // Visible for tests: expose whether span enrichment is wired (gate-on) without leaking the impl.
  SpanEnrichmentHook spanEnrichmentHook() {
    return spanEnrichmentHook;
  }

  /** Tells whether this provider produces evaluation telemetry, as captured at construction. */
  boolean isEvaluationTelemetryEnabled() {
    return evaluationTelemetryEnabled;
  }

  /** Tells whether this provider's evaluations are exposures, as captured at construction. */
  boolean isExposuresEnabled() {
    return exposuresEnabled;
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
    private boolean evaluationTelemetryEnabled = true;
    private boolean exposuresEnabled = true;
    private ExperimentationOptOutMode experimentationOptOutMode = ExperimentationOptOutMode.DEFAULT;
    private FlagSelection flagSelection;

    /**
     * Sets whether this provider produces evaluation telemetry: flag evaluation events, evaluation
     * metrics, and span enrichment. Enabled by default.
     *
     * <p>This does not change flag results, assignment, exposures, application hooks, or ordinary
     * application tracing. Use {@link #exposuresEnabled(boolean)} to control exposures. To turn
     * flag evaluation events off for the whole process instead, use {@code
     * DD_FLAGGING_EVALUATION_COUNTS_ENABLED=false}, which does not stop evaluation metrics or span
     * enrichment.
     *
     * @param enabled whether to produce evaluation telemetry; configure before constructing the
     *     provider.
     * @return these options.
     */
    public Options evaluationTelemetryEnabled(final boolean enabled) {
      this.evaluationTelemetryEnabled = enabled;
      return this;
    }

    /**
     * Sets whether evaluations through this provider are exposures. Enabled by default.
     *
     * <p>When disabled, an evaluation is not recorded, deduplicated, sent to Datadog, or reported
     * to an {@link ExposureHook} as an exposure, and it does not suppress a later exposure of the
     * same subject through a provider with exposures enabled. Flag results and assignment are
     * unchanged, so a provider with exposures disabled can look up the variant a subject would get
     * before the subject experiences it. With span enrichment on, the span records the evaluation
     * but not the subject.
     *
     * <p>This differs from {@code DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED=false}, which
     * applies to the whole process, keeps exposures for exposure hooks and deduplication, and only
     * stops sending them to Datadog. Use {@link #evaluationTelemetryEnabled(boolean)} to control
     * evaluation telemetry.
     *
     * @param enabled whether evaluations are exposures; configure before constructing the provider.
     * @return these options.
     */
    public Options exposuresEnabled(final boolean enabled) {
      this.exposuresEnabled = enabled;
      return this;
    }

    /**
     * Sets per-subject experimentation opt-out. {@link ExperimentationOptOutMode#DEFAULT} reads
     * {@code DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE}; every other mode, including {@code
     * DISABLED}, overrides configuration. The provider captures the mode at construction.
     * Unsupported configured modes throw {@link IllegalArgumentException} unless Feature Flags is
     * disabled.
     *
     * <p>Enabled modes read {@code dd_experimentation_opt_out_at} from the merged evaluation
     * context. Supply a timestamp String accepted by {@link
     * java.time.format.DateTimeFormatter#ISO_OFFSET_DATE_TIME}, for example {@code
     * Instant.now().toString()}. An {@code Instant} object, null, or any other invalid value
     * returns the application's default with {@code INVALID_CONTEXT} after configuration is ready,
     * even for ordinary or missing flags. Omit the attribute to permit experimentation; disabled
     * mode ignores it.
     *
     * <p>This controls experiment allocation selection, independently of evaluation telemetry
     * opt-out. The attribute remains available for targeting and reporting under the existing
     * exposure and evaluation-data settings.
     *
     * @param mode the mode; configure before constructing the provider.
     * @return these options.
     * @throws NullPointerException if mode is null.
     */
    public Options experimentationOptOutMode(final ExperimentationOptOutMode mode) {
      this.experimentationOptOutMode = requireNonNull(mode, "mode");
      return this;
    }

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
     * Loads only the selected flags: the flags that have any of the selection's tags, or whose key
     * matches any of its key patterns. Evaluating a flag outside the selection returns the default
     * value with {@code FLAG_NOT_FOUND}. Without a selection, or with an empty one, all the flags
     * are loaded.
     *
     * <p>Leading and trailing Unicode white space is trimmed from every value, empty values are
     * dropped, and duplicates are removed. A null list is treated as absent. Each list accepts at
     * most 31 values, and each tag and key pattern is at most 200 UTF-8 bytes after trimming. A
     * selection over a limit, holding a null or non-String value or an unpaired UTF-16 surrogate,
     * or making the request URL longer than 8192 bytes once percent-encoded, is not sent: the
     * provider logs an error and loads all the flags. The server can also ignore a selection, and
     * then returns all the flags.
     *
     * <p>The provider captures the selection at construction. The providers of a class loader share
     * one flag configuration: the first provider initialized decides the selection until the last
     * one shuts down, and a later provider with a different selection uses the first one. The first
     * such provider logs a warning, once per class loader. The {@code remote_config} configuration
     * source does not support a selection and loads all the flags.
     *
     * @param selection the flag selection, or {@code null} for all the flags.
     * @return these options.
     */
    public Options flagSelection(final FlagSelection selection) {
      this.flagSelection = selection;
      return this;
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
