package datadog.trace.api.telemetry;

import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Collects telemetry metrics produced by flag evaluation. */
public final class FlagEvaluationMetricCollector
    implements MetricCollector<FlagEvaluationMetricCollector.FlagEvaluationMetric> {
  private static final String METRIC_NAMESPACE = "tracers";
  private static final String CONTEXT_TRUNCATED_METRIC = "flagevaluation.context.truncated";
  private static final Counter[] COUNTERS = Counter.values();
  private static final FlagEvaluationMetricCollector INSTANCE = new FlagEvaluationMetricCollector();

  private final AtomicLongArray counts = new AtomicLongArray(COUNTERS.length);
  private final ConcurrentHashMap<String, AtomicLong> contextTruncationCounts =
      new ConcurrentHashMap<>();
  private final BlockingQueue<FlagEvaluationMetric> metricsQueue =
      new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);

  private FlagEvaluationMetricCollector() {}

  public static FlagEvaluationMetricCollector get() {
    return INSTANCE;
  }

  public enum Counter {
    DROPPED_QUEUE_OVERFLOW("flagevaluation.rows.dropped", "reason:queue_overflow"),
    DROPPED_CLOSED("flagevaluation.rows.dropped", "reason:closed"),
    DROPPED_DEGRADED_CAP("flagevaluation.rows.dropped", "reason:degraded_cap"),
    DROPPED_PAYLOAD_LIMIT("flagevaluation.rows.dropped", "reason:payload_limit"),
    DEGRADED_CARDINALITY_CAP("flagevaluation.rows.degraded", "reason:cardinality_cap"),
    DEGRADED_PAYLOAD_LIMIT("flagevaluation.rows.degraded", "reason:payload_limit"),
    PAYLOAD_SPLITS("flagevaluation.payload.splits", null);

    private final String name;
    private final String tag;

    Counter(String name, String tag) {
      this.name = name;
      this.tag = tag;
    }
  }

  public void count(Counter counter, long value) {
    if (value > 0) {
      counts.addAndGet(counter.ordinal(), value);
    }
  }

  /** Records the hook's canonical, comma-separated combination of truncation reasons. */
  public void countContextTruncated(String reason, long value) {
    if (value > 0) {
      contextTruncationCounts.computeIfAbsent(reason, key -> new AtomicLong()).addAndGet(value);
    }
  }

  @Override
  public void prepareMetrics() {
    for (Counter counter : COUNTERS) {
      if (metricsQueue.remainingCapacity() == 0) {
        return;
      }
      long value = counts.getAndSet(counter.ordinal(), 0);
      if (value > 0
          && !metricsQueue.offer(new FlagEvaluationMetric(counter.name, value, counter.tag))) {
        counts.addAndGet(counter.ordinal(), value);
        return;
      }
    }

    for (Map.Entry<String, AtomicLong> entry : contextTruncationCounts.entrySet()) {
      if (metricsQueue.remainingCapacity() == 0) {
        return;
      }
      long value = entry.getValue().getAndSet(0);
      if (value > 0
          && !metricsQueue.offer(
              new FlagEvaluationMetric(
                  CONTEXT_TRUNCATED_METRIC, value, "reason:" + entry.getKey()))) {
        entry.getValue().addAndGet(value);
        return;
      }
    }
  }

  @Override
  public Collection<FlagEvaluationMetric> drain() {
    if (metricsQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<FlagEvaluationMetric> drained = new ArrayList<>(metricsQueue.size());
    metricsQueue.drainTo(drained);
    return drained;
  }

  /** Clears all pending counters and metrics. Visible for testing only. */
  @VisibleForTesting
  public void resetForTesting() {
    for (int i = 0; i < counts.length(); i++) {
      counts.set(i, 0);
    }
    contextTruncationCounts.clear();
    metricsQueue.clear();
  }

  public static final class FlagEvaluationMetric extends MetricCollector.Metric {
    private FlagEvaluationMetric(String name, long value, String tag) {
      super(METRIC_NAMESPACE, true, name, "count", value, tag);
    }
  }
}
