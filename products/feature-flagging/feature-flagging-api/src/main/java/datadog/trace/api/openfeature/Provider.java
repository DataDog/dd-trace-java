package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.SECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
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
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Provider extends EventProvider implements Metadata {

  private static final Logger log = LoggerFactory.getLogger(Provider.class);
  static final String METADATA = "datadog-openfeature-provider";
  private static final String EVALUATOR_IMPL = "datadog.trace.api.openfeature.DDEvaluator";
  private static final long DEFAULT_INIT_TIMEOUT = 30;
  private volatile Evaluator evaluator;
  private final Options options;
  private final boolean telemetryEnabled;
  private final AtomicReference<InitializationState> initializationState =
      new AtomicReference<>(InitializationState.NOT_STARTED);
  private final FlagEvalMetrics flagEvalMetrics;
  private final FlagEvalMetricsHook flagEvalMetricsHook;
  // Span enrichment: null unless the gate is on, so the feature has no idle overhead when off.
  private final SpanEnrichmentHook spanEnrichmentHook;
  // Precomputed hook list returned by getProviderHooks() on every evaluation. Immutable and built
  // once so gate-off evaluation allocates nothing on this hot path.
  private final List<Hook> providerHooks;

  public Provider() {
    this(new Options(), null);
  }

  public Provider(final Options options) {
    this(options, null);
  }

  Provider(final Options options, final Evaluator evaluator) {
    this(options, evaluator, null);
  }

  /**
   * @param spanEnrichmentEnabledOverride when non-null, forces the span-enrichment gate (test
   *     seam); when null, the gate is read via {@link SpanEnrichmentGate}.
   */
  Provider(
      final Options options,
      final Evaluator evaluator,
      final Boolean spanEnrichmentEnabledOverride) {
    this.options = options;
    this.telemetryEnabled = options.isTelemetryEnabled();
    this.evaluator = evaluator;

    FlagEvalMetrics metrics = null;
    FlagEvalMetricsHook metricsHook = null;
    SpanEnrichmentHook enrichmentHook = null;
    final List<Hook> hooks = new ArrayList<>(3);
    if (telemetryEnabled) {
      try {
        metrics = new FlagEvalMetrics();
        metricsHook = new FlagEvalMetricsHook(metrics);
        hooks.add(metricsHook);
      } catch (LinkageError | Exception e) {
        // This outer catch fires when the metrics helper itself can't load (OTel API absent).
        log.warn("Evaluation metrics unavailable — OTel API classes not on classpath", e);
      }

      // EVP flagevaluation hook: registered when provider telemetry is enabled; no-op when the
      // writer is absent (killswitch off). The writer is resolved lazily on each call.
      try {
        final Hook flagEvalLoggingHook = buildFlagEvalLoggingHook();
        if (flagEvalLoggingHook != null) {
          hooks.add(flagEvalLoggingHook);
        }
      } catch (LinkageError | Exception e) {
        // Keep older bootstrap/API combinations working: EVP recording is best-effort.
      }

      // Span enrichment is wired ONLY when the gate is on — off means no capture hook and no idle
      // per-evaluation overhead.
      final boolean spanEnrichmentEnabled =
          spanEnrichmentEnabledOverride != null
              ? spanEnrichmentEnabledOverride
              : SpanEnrichmentGate.isEnabled();
      enrichmentHook = spanEnrichmentEnabled ? new SpanEnrichmentHook() : null;
      if (enrichmentHook != null) {
        hooks.add(enrichmentHook);
      }
    }

    this.flagEvalMetrics = metrics;
    this.flagEvalMetricsHook = metricsHook;
    this.spanEnrichmentHook = enrichmentHook;

    // Precompute the immutable hook list once so getProviderHooks() (called on every evaluation)
    // allocates nothing, including when telemetry is disabled.
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
    } catch (final OpenFeatureError e) {
      markInitializationError();
      throw e;
    } catch (final Throwable e) {
      markInitializationError();
      throw new FatalError("Failed to initialize provider, is the tracer configured?", e);
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

  private Evaluator buildEvaluator() throws Exception {
    if (evaluator != null) {
      return evaluator;
    }
    final Class<?> evaluatorClass = loadEvaluatorClass();
    final Constructor<?> ctor = evaluatorClass.getConstructor(Runnable.class, boolean.class);
    return (Evaluator) ctor.newInstance((Runnable) this::onConfigurationChange, telemetryEnabled);
  }

  @Override
  public List<Hook> getProviderHooks() {
    return providerHooks;
  }

  Hook buildFlagEvalLoggingHook() {
    return FlagEvalLoggingHook.INSTANCE;
  }

  @Override
  public void shutdown() {
    if (flagEvalMetrics != null) {
      flagEvalMetrics.shutdown();
    }
    // Span enrichment needs no provider-close cleanup here: the capture hook holds no tracer state.
    // The agent-side write tier owns the interceptor and per-trace state and is torn down with the
    // feature-flagging subsystem, not per provider.
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

  @SuppressForbidden // Class#forName(String) used to lazy-load the evaluator implementation
  protected Class<?> loadEvaluatorClass() throws ClassNotFoundException {
    return Class.forName(EVALUATOR_IMPL);
  }

  private enum InitializationState {
    NOT_STARTED,
    INITIALIZING,
    INITIAL_CONFIG_RECEIVED,
    READY,
    ERROR
  }

  public static class Options {

    private long timeout = DEFAULT_INIT_TIMEOUT;
    private TimeUnit unit = SECONDS;
    private boolean telemetryEnabled = true;

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

    /**
     * Enables or disables Datadog telemetry produced by this provider.
     *
     * <p>When disabled, evaluations still use the current Datadog configuration and return normal
     * results, but the provider emits no exposures, EVP flag-evaluation events, OpenTelemetry
     * evaluation metrics, or APM span enrichment. This is useful with OpenFeature domains: bind a
     * telemetry-enabled provider to a live domain and a telemetry-disabled provider to a domain
     * used only to inspect evaluations.
     *
     * @param telemetryEnabled whether this provider should produce Datadog telemetry
     * @return these options
     */
    public Options telemetryEnabled(final boolean telemetryEnabled) {
      this.telemetryEnabled = telemetryEnabled;
      return this;
    }

    public boolean isTelemetryEnabled() {
      return telemetryEnabled;
    }
  }
}
