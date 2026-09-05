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
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Provider extends EventProvider implements Metadata {

  private static final Logger log = LoggerFactory.getLogger(Provider.class);
  static final String METADATA = "datadog-openfeature-provider";
  private static final String EVALUATOR_IMPL = "datadog.trace.api.openfeature.DDEvaluator";
  private static final String MINIMUM_OPENFEATURE_SDK_VERSION = "1.20.1";
  private static final String OPENFEATURE_SDK_POM_PROPERTIES =
      "/META-INF/maven/dev.openfeature/sdk/pom.properties";
  private static final String UNKNOWN_VERSION = "unknown";

  private static final Options DEFAULT_OPTIONS = new Options().initTimeout(30, SECONDS);
  private volatile Evaluator evaluator;
  private final Options options;
  private final AtomicReference<InitializationState> initializationState =
      new AtomicReference<>(InitializationState.NOT_STARTED);
  private final AtomicBoolean openFeatureSdkCompatibilityWarningLogged = new AtomicBoolean();
  private final FlagEvalMetrics flagEvalMetrics;
  private final FlagEvalMetricsHook flagEvalMetricsHook;
  // Span enrichment: null unless the gate is on, so the feature has no idle overhead when off.
  private final SpanEnrichmentHook spanEnrichmentHook;
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
   * @param spanEnrichmentEnabledOverride when non-null, forces the span-enrichment gate (test
   *     seam); when null, the gate is read via {@link SpanEnrichmentGate}.
   */
  Provider(
      final Options options,
      final Evaluator evaluator,
      final Boolean spanEnrichmentEnabledOverride) {
    this.options = options;
    this.evaluator = evaluator;
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
            : SpanEnrichmentGate.isEnabled();
    this.spanEnrichmentHook = spanEnrichmentEnabled ? new SpanEnrichmentHook() : null;

    // Precompute the immutable hook list once so getProviderHooks() (called on every evaluation)
    // allocates nothing, including when the gate is off.
    final List<Hook> hooks = new ArrayList<>(3);
    if (flagEvalMetricsHook != null) {
      hooks.add(flagEvalMetricsHook);
    }
    // EVP flagevaluation hook: always registered; no-op when writer is absent (killswitch off).
    // Writer is resolved lazily from FeatureFlaggingGateway.getFlagEvalWriter() on each call.
    try {
      final Hook flagEvalLoggingHook = buildFlagEvalLoggingHook();
      if (flagEvalLoggingHook != null) {
        hooks.add(flagEvalLoggingHook);
      }
    } catch (LinkageError | Exception e) {
      // Keep older bootstrap/API combinations working: EVP recording is best-effort.
    }
    if (spanEnrichmentHook != null) {
      hooks.add(spanEnrichmentHook);
    }
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
      emitProviderEvent(ProviderEvent.PROVIDER_READY, "Provider ready", null);
      return;
    }
    if (initializationState.get() != InitializationState.READY) {
      return;
    }
    emitProviderEvent(
        ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, "New configuration received", null);
  }

  private void onConfigurationUnavailable() {
    if (initializationState.compareAndSet(
        InitializationState.INITIAL_CONFIG_RECEIVED, InitializationState.ERROR)) {
      return;
    }
    if (!initializationState.compareAndSet(InitializationState.READY, InitializationState.ERROR)) {
      return;
    }
    emitProviderEvent(
        ProviderEvent.PROVIDER_ERROR, "Configuration unavailable", ErrorCode.PROVIDER_NOT_READY);
  }

  private void emitProviderEvent(
      final ProviderEvent event, final String message, final ErrorCode errorCode) {
    try {
      if (errorCode == null) {
        emit(event, ProviderEventDetails.builder().message(message).build());
      } else {
        emit(event, ProviderEventDetails.builder().message(message).errorCode(errorCode).build());
      }
    } catch (final LinkageError error) {
      if (openFeatureSdkCompatibilityWarningLogged.compareAndSet(false, true)) {
        reportOpenFeatureSdkIncompatibility(error);
      }
    }
  }

  void reportOpenFeatureSdkIncompatibility(final LinkageError error) {
    log.warn(openFeatureSdkCompatibilityWarning(error));
    log.debug("OpenFeature SDK compatibility failure", error);
  }

  static String openFeatureSdkCompatibilityWarning(final LinkageError error) {
    return "Unable to emit OpenFeature provider events because the loaded OpenFeature SDK is "
        + "incompatible (detected version: "
        + openFeatureSdkVersion()
        + "). Datadog requires dev.openfeature:sdk version "
        + MINIMUM_OPENFEATURE_SDK_VERSION
        + " or later. Upgrade the OpenFeature SDK dependency. Further provider event emission "
        + "failures will be suppressed. Cause: "
        + error;
  }

  static String openFeatureSdkVersion() {
    try {
      final Package sdkPackage = EventProvider.class.getPackage();
      if (sdkPackage != null && sdkPackage.getImplementationVersion() != null) {
        return sdkPackage.getImplementationVersion();
      }
      try (InputStream input =
          EventProvider.class.getResourceAsStream(OPENFEATURE_SDK_POM_PROPERTIES)) {
        if (input != null) {
          final String version = loadOpenFeatureSdkVersion(input);
          if (version != null && !version.isEmpty()) {
            return version;
          }
        }
      }
    } catch (final IOException | RuntimeException | LinkageError ignored) {
      // Version detection is best-effort and must not interfere with compatibility handling.
    }
    return UNKNOWN_VERSION;
  }

  private static String loadOpenFeatureSdkVersion(final InputStream input) throws IOException {
    final Properties properties = new Properties();
    properties.load(input);
    return properties.getProperty("version");
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
