package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public abstract class FeatureFlaggingGateway {

  public interface ConfigListener extends Consumer<ServerConfiguration> {}

  public interface ActivationListener {
    void activate();
  }

  public interface ExposureListener extends Consumer<ExposureEvent> {}

  public interface SpanEnrichmentListener extends Consumer<SpanEnrichmentEvent> {}

  private static final List<ConfigListener> CONFIG_LISTENERS = new CopyOnWriteArrayList<>();
  private static final List<ActivationListener> ACTIVATION_LISTENERS = new CopyOnWriteArrayList<>();
  private static final List<ExposureListener> EXPOSURE_LISTENERS = new CopyOnWriteArrayList<>();
  private static final List<SpanEnrichmentListener> SPAN_ENRICHMENT_LISTENERS =
      new CopyOnWriteArrayList<>();

  private static final AtomicReference<ServerConfiguration> CURRENT_CONFIG =
      new AtomicReference<>();

  /**
   * The active EVP flagevaluation writer. Registered by {@code FlagEvaluationWriterImpl.start()}
   * when the killswitch {@code DD_FLAGGING_EVALUATION_COUNTS_ENABLED} is on (default). Read by
   * {@code FlagEvalLoggingHook} to route evaluations into the two-tier aggregator. {@code null}
   * when the EVP path is disabled.
   */
  private static final AtomicReference<FlagEvaluationWriter> FLAG_EVAL_WRITER =
      new AtomicReference<>();

  private static volatile boolean flagEvalEnqueueEnabled = true;
  private static volatile Backend backend;

  private FeatureFlaggingGateway() {}

  /**
   * Registers the agent backend.
   *
   * @param registered the backend, or {@code null} to unregister it.
   */
  public static void register(@Nullable final Backend registered) {
    backend = registered;
  }

  /**
   * @return the agent backend, or {@code null} if Feature Flags is not started.
   */
  @Nullable
  public static Backend backend() {
    return backend;
  }

  public static void addConfigListener(final ConfigListener listener) {
    CONFIG_LISTENERS.add(listener);
    final ServerConfiguration current = CURRENT_CONFIG.get();
    if (current != null) {
      listener.accept(current);
    }
  }

  public static void removeConfigListener(final ConfigListener listener) {
    CONFIG_LISTENERS.remove(listener);
  }

  public static void dispatch(final ServerConfiguration config) {
    CURRENT_CONFIG.set(config);
    CONFIG_LISTENERS.forEach(listener -> listener.accept(config));
  }

  public static void addActivationListener(final ActivationListener listener) {
    ACTIVATION_LISTENERS.add(listener);
  }

  public static void removeActivationListener(final ActivationListener listener) {
    ACTIVATION_LISTENERS.remove(listener);
  }

  /** Signals that application code initialized the Datadog OpenFeature provider. */
  public static void activate() {
    ACTIVATION_LISTENERS.forEach(ActivationListener::activate);
  }

  public static void addExposureListener(final ExposureListener listener) {
    EXPOSURE_LISTENERS.add(listener);
  }

  public static void removeExposureListener(final ExposureListener listener) {
    EXPOSURE_LISTENERS.remove(listener);
  }

  public static void dispatch(final ExposureEvent event) {
    EXPOSURE_LISTENERS.forEach(listener -> listener.accept(event));
  }

  /**
   * Registers the active EVP flagevaluation writer. Called by {@code
   * FlagEvaluationWriterImpl.start()} when the feature is enabled. Replaces any previously
   * registered writer.
   *
   * @param writer the writer to register, or {@code null} to deregister
   */
  public static void setFlagEvalWriter(final FlagEvaluationWriter writer) {
    FLAG_EVAL_WRITER.set(writer);
  }

  /**
   * Enables or disables enqueueing EVP flagevaluation events on the OpenFeature hook path. This is
   * populated from {@code DD_FLAGGING_EVALUATION_COUNTS_ENABLED} at feature-flagging startup and
   * cleared during shutdown before the writer drains.
   */
  public static void setFlagEvaluationEnqueueEnabled(final boolean enabled) {
    flagEvalEnqueueEnabled = enabled;
  }

  /**
   * Returns the active EVP flagevaluation writer, or {@code null} when disabled (killswitch off or
   * not yet started).
   */
  public static FlagEvaluationWriter getFlagEvalWriter() {
    return FLAG_EVAL_WRITER.get();
  }

  /** Returns whether EVP flagevaluation hook events may be enqueued. */
  public static boolean isFlagEvaluationEnqueueEnabled() {
    return flagEvalEnqueueEnabled;
  }

  public static void addSpanEnrichmentListener(final SpanEnrichmentListener listener) {
    SPAN_ENRICHMENT_LISTENERS.add(listener);
  }

  public static void removeSpanEnrichmentListener(final SpanEnrichmentListener listener) {
    SPAN_ENRICHMENT_LISTENERS.remove(listener);
  }

  public static void dispatch(final SpanEnrichmentEvent event) {
    SPAN_ENRICHMENT_LISTENERS.forEach(listener -> listener.accept(event));
  }

  /** The agent services exposed to the Feature Flags SDK. */
  public interface Backend {
    /**
     * Looks up a setting from the agent configuration.
     *
     * @param key the setting key, using the {@code dd.} system property notation without prefix.
     * @return the setting value, or {@code null} if not set.
     */
    @Nullable
    String setting(String key);

    /**
     * @return whether Remote Configuration is available.
     */
    boolean isRemoteConfigAvailable();

    /**
     * Subscribes to the Feature Flags Remote Configuration product.
     *
     * @param listener the listener receiving raw configuration documents, or {@code null} when the
     *     configuration is removed.
     * @return the handle to close to unsubscribe.
     */
    AutoCloseable subscribeRemoteConfig(Consumer<byte[]> listener);

    /**
     * @return whether the Datadog Agent event platform proxy is available.
     */
    boolean isEventProxyAvailable();

    /**
     * Posts an event payload through the Datadog Agent event platform proxy.
     *
     * @param route the event platform route.
     * @param json the UTF-8 JSON payload.
     * @return {@code true} if the payload was delivered, {@code false} if the proxy definitively
     *     rejected it as unavailable.
     * @throws IOException if the payload could not be delivered.
     */
    boolean postEvent(String route, byte[] json) throws IOException;

    /**
     * Increments a health counter.
     *
     * @param metric the metric name.
     * @param value the increment.
     * @param reason the optional {@code reason} tag value.
     */
    void count(String metric, long value, @Nullable String reason);

    /**
     * Records an evaluation that resolved to a split with a serial id on the local root span.
     *
     * @param serialId the split serial id.
     * @param doLog whether the allocation logs exposures.
     * @param targetingKey the optional targeting key.
     */
    void enrichSerialId(int serialId, boolean doLog, @Nullable String targetingKey);

    /**
     * Records an evaluation that resolved to its runtime default on the local root span.
     *
     * @param flagKey the flag key.
     * @param defaultValue the native default value.
     */
    void enrichRuntimeDefault(String flagKey, @Nullable Object defaultValue);
  }
}
