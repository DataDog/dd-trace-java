package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.GlobalTracer;
import datadog.trace.config.inversion.ConfigHelper;
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

  /**
   * Environment variable form of {@link
   * datadog.trace.api.config.FeatureFlaggingConfig#SPAN_ENRICHMENT_ENABLED}. Distinct from the
   * provider-enabled gate; OFF by default (experimental opt-in).
   */
  static final String SPAN_ENRICHMENT_ENABLED_ENV =
      "DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED";

  private static final Options DEFAULT_OPTIONS = new Options().initTimeout(30, SECONDS);
  private volatile Evaluator evaluator;
  private final Options options;
  private final AtomicReference<InitializationState> initializationState =
      new AtomicReference<>(InitializationState.NOT_STARTED);
  private final FlagEvalMetrics flagEvalMetrics;
  private final FlagEvalHook flagEvalHook;
  // Span enrichment: null unless the gate is on, so the feature has no idle overhead when off.
  private final SpanEnrichmentHook spanEnrichmentHook;
  private final SpanEnrichmentStates spanEnrichmentStates;
  // Precomputed hook list returned by getProviderHooks() on every evaluation. Immutable and built
  // once so gate-off evaluation allocates nothing on this hot path.
  private final List<Hook> providerHooks;

  public Provider() {
    this(DEFAULT_OPTIONS, null);
  }

  public Provider(final Options options) {
    this(options, null);
  }

  Provider(final Options options, final Evaluator evaluator) {
    this(options, evaluator, null);
  }

  /**
   * Registers a {@link SpanEnrichmentInterceptor} with the running tracer, returning {@code true}
   * when it was added and {@code false} when the tracer rejected it (e.g. the interceptor is
   * already registered, or the global tracer is the no-op placeholder). Injectable so tests can
   * drive registration deterministically without mutating the global tracer (mirrors the {@code
   * spanEnrichmentEnabledOverride} seam).
   */
  interface TraceInterceptorRegistrar {
    boolean register(SpanEnrichmentInterceptor interceptor);
  }

  /**
   * @param spanEnrichmentEnabledOverride when non-null, forces the span-enrichment gate (test
   *     seam); when null, the gate is read from {@link #SPAN_ENRICHMENT_ENABLED_ENV}.
   */
  Provider(
      final Options options,
      final Evaluator evaluator,
      final Boolean spanEnrichmentEnabledOverride) {
    this(
        options,
        evaluator,
        spanEnrichmentEnabledOverride,
        interceptor -> GlobalTracer.get().addTraceInterceptor(interceptor));
  }

  /**
   * @param registrar registers the span-enrichment interceptor with the tracer; injectable for
   *     tests (see {@link TraceInterceptorRegistrar}).
   */
  Provider(
      final Options options,
      final Evaluator evaluator,
      final Boolean spanEnrichmentEnabledOverride,
      final TraceInterceptorRegistrar registrar) {
    this.options = options;
    this.evaluator = evaluator;
    FlagEvalMetrics metrics = null;
    FlagEvalHook hook = null;
    try {
      metrics = new FlagEvalMetrics();
      hook = new FlagEvalHook(metrics);
    } catch (LinkageError | Exception e) {
      // This outer catch fires when the metrics helper itself can't load (OTel API absent).
      log.warn("Evaluation metrics unavailable — OTel API classes not on classpath", e);
    }
    this.flagEvalMetrics = metrics;
    this.flagEvalHook = hook;

    // Span enrichment is wired ONLY when the gate is on. When off, no hook/state is constructed and
    // there is no idle per-evaluation or per-span overhead.
    final boolean spanEnrichmentEnabled =
        spanEnrichmentEnabledOverride != null
            ? spanEnrichmentEnabledOverride
            : isSpanEnrichmentEnabled();
    SpanEnrichmentHook seHook = null;
    SpanEnrichmentStates seStates = null;
    if (spanEnrichmentEnabled) {
      try {
        // Per-provider state store, shared with this provider's capture hook. The single,
        // process-wide interceptor is registered once (reconfiguration-safe) and rebound to this
        // provider's store. A later gate-on provider rebinds it to its own store; this provider's
        // shutdown only unbinds if it is still the active provider, so reconfiguration never
        // permanently disables enrichment and providers never clobber each other's live state.
        seStates = new SpanEnrichmentStates();
        SpanEnrichmentInterceptor.ensureRegistered(registrar);
        SpanEnrichmentInterceptor.INSTANCE.bind(seStates);
        seHook = new SpanEnrichmentHook(seStates);
      } catch (LinkageError | Exception e) {
        // Tracer classes absent (e.g. API-only classpath): degrade to no span enrichment.
        log.warn("Span enrichment unavailable — tracer classes not on classpath", e);
        seHook = null;
        seStates = null;
      }
    }
    this.spanEnrichmentHook = seHook;
    this.spanEnrichmentStates = seStates;

    // Precompute the immutable hook list once so getProviderHooks() (called on every evaluation)
    // allocates nothing, including when the gate is off.
    final List<Hook> hooks = new ArrayList<>(2);
    if (flagEvalHook != null) {
      hooks.add(flagEvalHook);
    }
    if (seHook != null) {
      hooks.add(seHook);
    }
    this.providerHooks =
        hooks.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(hooks);
  }

  private static boolean isSpanEnrichmentEnabled() {
    try {
      final String value = ConfigHelper.env(SPAN_ENRICHMENT_ENABLED_ENV);
      return "true".equalsIgnoreCase(value) || "1".equals(value);
    } catch (final Throwable t) {
      return false; // never let config reading break provider construction
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
    final Constructor<?> ctor = evaluatorClass.getConstructor(Runnable.class);
    return (Evaluator) ctor.newInstance((Runnable) this::onConfigurationChange);
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
    // Provider-close cleanup for span enrichment: the tracer has no interceptor-removal API, so we
    // unbind this provider's store from the process-wide interceptor (which clears the store and
    // makes the interceptor inert until a new provider rebinds it). The unbind is a no-op if a
    // newer provider has already rebound the interceptor, so we never wipe another provider's
    // in-flight state.
    if (spanEnrichmentStates != null) {
      SpanEnrichmentInterceptor.INSTANCE.unbind(spanEnrichmentStates);
    }
    if (evaluator != null) {
      evaluator.shutdown();
    }
  }

  // Visible for tests: expose whether span enrichment is wired (gate-on) without leaking the impls.
  SpanEnrichmentHook spanEnrichmentHook() {
    return spanEnrichmentHook;
  }

  SpanEnrichmentStates spanEnrichmentStates() {
    return spanEnrichmentStates;
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

  @SuppressForbidden // Class#forName(String) used to lazy load internal-api dependencies
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

    private long timeout;
    private TimeUnit unit;

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
