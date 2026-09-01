package datadog.trace.api.telemetry;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects telemetry metrics for LLM Observability spans.
 *
 * <p>Counts are aggregated per tag combination in-process and emitted as one point per metrics
 * interval.
 */
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
  private static final String HAS_SESSION_ID_TRUE = "has_session_id:1";
  private static final String HAS_SESSION_ID_FALSE = "has_session_id:0";

  /**
   * Upper bound on the number of distinct tag combinations tracked. Tag values are drawn from
   * bounded sets (integrations, span kinds, and four booleans), so legitimate cardinality is in the
   * low hundreds at the 8-integration scale the tag caches are sized for; this only guards against
   * an unexpected high-cardinality source. Entries are never removed (see {@link
   * #prepareMetrics()}), so this is a lifetime ceiling, not a concurrent one.
   */
  static final int MAX_TAG_COMBINATIONS = 512;

  private final BlockingQueue<LLMObsMetric> metricsQueue;
  private final DDCache<String, String> integrationTagCache;
  private final DDCache<String, String> spanKindTagCache;

  /**
   * Counter per tag combination, aggregated in-process and flushed once per metrics interval by
   * {@link #prepareMetrics()}.
   */
  private final ConcurrentHashMap<List<String>, LongAdder> spanFinishedCounters;

  private LLMObsMetricCollector() {
    this.metricsQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
    this.integrationTagCache = DDCaches.newFixedSizeCache(8);
    this.spanKindTagCache = DDCaches.newFixedSizeCache(8);
    this.spanFinishedCounters = new ConcurrentHashMap<>();
  }

  /**
   * Record a span finished metric for LLMObs telemetry.
   *
   * <p>This only increments an in-process counter. The counter is converted into a single telemetry
   * metric per tag combination by {@link #prepareMetrics()}, once per metrics interval.
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

    LongAdder counter = spanFinishedCounters.get(tags);
    if (counter == null) {
      // Soft bound: concurrent recorders may overshoot slightly, which is fine for a guard.
      if (spanFinishedCounters.size() >= MAX_TAG_COMBINATIONS) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Dropping telemetry metric {} for {}: tag combination limit ({}) reached",
              SPAN_FINISHED_METRIC,
              integration,
              MAX_TAG_COMBINATIONS);
        }
        return;
      }
      counter = spanFinishedCounters.computeIfAbsent(tags, key -> new LongAdder());
    }
    counter.increment();
  }

  @Override
  public void prepareMetrics() {
    // Entries are never removed: a recorder thread may already hold a reference to a LongAdder, so
    // removing it here would silently drop a concurrent increment. Tag values come from bounded
    // sets, so retaining idle combinations costs at most MAX_TAG_COMBINATIONS entries.
    for (Map.Entry<List<String>, LongAdder> entry : spanFinishedCounters.entrySet()) {
      long value = entry.getValue().sumThenReset();
      if (value == 0) {
        continue;
      }
      LLMObsMetric metric =
          new LLMObsMetric(
              METRIC_NAMESPACE,
              true,
              SPAN_FINISHED_METRIC,
              COUNT_METRIC_TYPE,
              value,
              entry.getKey());
      if (!metricsQueue.offer(metric)) {
        // Queue is full; give the count back to the counter so it is reported in a later interval
        // instead of being lost, and stop staging for now.
        entry.getValue().add(value);
        log.debug("Unable to add telemetry metric {}: queue is full", SPAN_FINISHED_METRIC);
        break;
      }
    }
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

  /** Clears all staged counters and metrics. Visible for testing only. */
  @VisibleForTesting
  public void resetForTesting() {
    spanFinishedCounters.clear();
    metricsQueue.clear();
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
