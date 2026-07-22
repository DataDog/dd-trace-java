package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public abstract class FeatureFlaggingGateway {

  public interface ConfigListener extends Consumer<ServerConfiguration> {}

  public interface ExposureListener extends Consumer<ExposureEvent> {}

  public interface SpanEnrichmentListener extends Consumer<SpanEnrichmentEvent> {}

  private static final List<ConfigListener> CONFIG_LISTENERS = new CopyOnWriteArrayList<>();
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

  private FeatureFlaggingGateway() {}

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

  /**
   * Returns whether the currently active UFC environment has {@code observeFullEvaluationData}
   * enabled. {@code false} (privacy-preserving default) when no UFC has been dispatched yet or when
   * the field was absent/false on the last dispatched configuration.
   */
  public static boolean isObserveFullEvaluationDataEnabled() {
    final ServerConfiguration current = CURRENT_CONFIG.get();
    return current != null && current.observeFullEvaluationData;
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
}
