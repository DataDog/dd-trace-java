package datadog.trace.api.telemetry;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LLMObsMetricCollector
    implements MetricCollector<LLMObsMetricCollector.LLMObsMetric> {
  private static final String METRIC_NAMESPACE = "mlobs";

  private static final Logger log = LoggerFactory.getLogger(LLMObsMetricCollector.class);
  private static final LLMObsMetricCollector INSTANCE = new LLMObsMetricCollector();

  public static LLMObsMetricCollector get() {
    return INSTANCE;
  }

  public static final String SPAN_FINISHED_METRIC = "span.finished";
  public static final String USER_PROCESSOR_CALLED_METRIC = "user_processor_called";
  public static final String FEEDBACK_SUBMITTED_METRIC = "feedback_submitted";
  public static final String COUNT_METRIC_TYPE = "count";

  /** Tag value used when a submission failed before the real value could be determined. */
  private static final String OTHER = "other";

  private static final String IS_ROOT_SPAN_TRUE = "is_root_span:1";
  private static final String IS_ROOT_SPAN_FALSE = "is_root_span:0";
  private static final String AUTOINSTRUMENTED_TRUE = "autoinstrumented:1";
  private static final String AUTOINSTRUMENTED_FALSE = "autoinstrumented:0";
  private static final String ERROR_TRUE = "error:1";
  private static final String ERROR_FALSE = "error:0";
  private static final String HAS_SESSION_ID_TRUE = "has_session_id:1";
  private static final String HAS_SESSION_ID_FALSE = "has_session_id:0";

  private final BlockingQueue<LLMObsMetric> metricsQueue;
  private final DDCache<String, String> integrationTagCache;
  private final DDCache<String, String> spanKindTagCache;

  private LLMObsMetricCollector() {
    this.metricsQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
    this.integrationTagCache = DDCaches.newFixedSizeCache(8);
    this.spanKindTagCache = DDCaches.newFixedSizeCache(8);
  }

  /**
   * Record a span finished metric for LLMObs telemetry.
   *
   * @param integration the integration name (e.g., "openai")
   * @param spanKind the span kind (e.g., "llm", "embedding")
   * @param isRootSpan whether this is a root span
   * @param isAutoInstrumented whether this span was auto-instrumented
   * @param hasError whether the span had an error
   * @param hasSessionId whether a session id was provided for this span
   */
  public void recordSpanFinished(
      String integration,
      String spanKind,
      boolean isRootSpan,
      boolean isAutoInstrumented,
      boolean hasError,
      boolean hasSessionId) {
    String integrationTag =
        integrationTagCache.computeIfAbsent(integration, key -> "integration:" + key);
    String spanKindTag = spanKindTagCache.computeIfAbsent(spanKind, key -> "span_kind:" + key);

    List<String> tags =
        Arrays.asList(
            integrationTag,
            spanKindTag,
            isRootSpan ? IS_ROOT_SPAN_TRUE : IS_ROOT_SPAN_FALSE,
            isAutoInstrumented ? AUTOINSTRUMENTED_TRUE : AUTOINSTRUMENTED_FALSE,
            hasError ? ERROR_TRUE : ERROR_FALSE,
            hasSessionId ? HAS_SESSION_ID_TRUE : HAS_SESSION_ID_FALSE);
    LLMObsMetric metric =
        new LLMObsMetric(METRIC_NAMESPACE, true, SPAN_FINISHED_METRIC, COUNT_METRIC_TYPE, 1L, tags);
    if (!metricsQueue.offer(metric)) {
      log.debug("Unable to add telemetry metric {} for {}", SPAN_FINISHED_METRIC, integration);
    }
  }

  /**
   * Records that a user-provided LLM Observability span processor was called.
   *
   * @param error whether the processor failed
   */
  public void recordUserProcessorCalled(boolean error) {
    LLMObsMetric metric =
        new LLMObsMetric(
            METRIC_NAMESPACE,
            true,
            USER_PROCESSOR_CALLED_METRIC,
            COUNT_METRIC_TYPE,
            1L,
            Collections.singletonList(error ? ERROR_TRUE : ERROR_FALSE));
    if (!metricsQueue.offer(metric)) {
      log.debug("Unable to add telemetry metric {}", USER_PROCESSOR_CALLED_METRIC);
    }
  }

  /**
   * Record an end-user feedback submission attempt, successful or not.
   *
   * <p>Mirrors {@code record_llmobs_submit_feedback} in dd-trace-py: rejected submissions are
   * counted too, tagged with the validation error, so that adoption and SDK misuse are both
   * visible.
   *
   * @param metricType the feedback metric type (e.g. "boolean"), or null if it could not be
   *     determined
   * @param targetType the wire key of the feedback target (e.g. "span_id"), or null if it could not
   *     be determined
   * @param error the validation error code (e.g. "invalid_submitter"), or null if the submission
   *     was accepted
   */
  public void recordFeedbackSubmitted(
      @Nullable String metricType, @Nullable String targetType, @Nullable String error) {
    List<String> tags = new ArrayList<>(4);
    tags.add(error == null ? ERROR_FALSE : ERROR_TRUE);
    if (error != null) {
      tags.add("error_type:" + error);
    }
    tags.add("metric_type:" + (metricType == null ? OTHER : metricType));
    tags.add("target_type:" + (targetType == null ? OTHER : targetType));

    LLMObsMetric metric =
        new LLMObsMetric(
            METRIC_NAMESPACE, true, FEEDBACK_SUBMITTED_METRIC, COUNT_METRIC_TYPE, 1L, tags);
    if (!metricsQueue.offer(metric)) {
      log.debug("Unable to add telemetry metric {}", FEEDBACK_SUBMITTED_METRIC);
    }
  }

  @Override
  public void prepareMetrics() {
    // metrics are added directly via recordSpanFinished; no additional preparation needed
  }

  @Override
  public Collection<LLMObsMetric> drain() {
    if (this.metricsQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<LLMObsMetric> drained = new ArrayList<>(this.metricsQueue.size());
    this.metricsQueue.drainTo(drained);
    return drained;
  }

  public static class LLMObsMetric extends MetricCollector.Metric {
    public LLMObsMetric(
        String namespace,
        boolean common,
        String metricName,
        String type,
        Number value,
        List<String> tags) {
      super(namespace, common, metricName, type, value, tags);
    }
  }
}
