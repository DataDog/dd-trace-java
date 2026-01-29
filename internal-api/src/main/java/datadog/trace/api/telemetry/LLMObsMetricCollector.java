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
  public static final String COUNT_METRIC_TYPE = "count";

  private static final String IS_ROOT_SPAN_TRUE = "is_root_span:1";
  private static final String IS_ROOT_SPAN_FALSE = "is_root_span:0";
  private static final String AUTOINSTRUMENTED_TRUE = "autoinstrumented:1";
  private static final String AUTOINSTRUMENTED_FALSE = "autoinstrumented:0";
  private static final String ERROR_TRUE = "error:1";
  private static final String ERROR_FALSE = "error:0";

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
   */
  public void recordSpanFinished(
      String integration,
      String spanKind,
      boolean isRootSpan,
      boolean isAutoInstrumented,
      boolean hasError) {
    String integrationTag =
        integrationTagCache.computeIfAbsent(integration, key -> "integration:" + key);
    String spanKindTag = spanKindTagCache.computeIfAbsent(spanKind, key -> "span_kind:" + key);

    List<String> tags =
        Arrays.asList(
            integrationTag,
            spanKindTag,
            isRootSpan ? IS_ROOT_SPAN_TRUE : IS_ROOT_SPAN_FALSE,
            isAutoInstrumented ? AUTOINSTRUMENTED_TRUE : AUTOINSTRUMENTED_FALSE,
            hasError ? ERROR_TRUE : ERROR_FALSE);

    LLMObsMetric metric =
        new LLMObsMetric(METRIC_NAMESPACE, true, SPAN_FINISHED_METRIC, COUNT_METRIC_TYPE, 1L, tags);
    if (!metricsQueue.offer(metric)) {
      log.debug("Unable to add telemetry metric {} for {}", SPAN_FINISHED_METRIC, integration);
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
