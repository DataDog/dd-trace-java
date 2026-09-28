package datadog.trace.api.featureflag.flagevaluation;

import datadog.metrics.api.Accumulator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Flag evaluation counters owned by the producer and drained periodically by telemetry. */
public final class FlagEvaluationMetrics {
  private static final FlagEvaluationMetrics INSTANCE = new FlagEvaluationMetrics();

  private final Accumulator<Metric> counts = Accumulator.of(Metric.class);
  private final ConcurrentHashMap<String, AtomicLong> contextTruncations =
      new ConcurrentHashMap<>();

  private FlagEvaluationMetrics() {}

  public static FlagEvaluationMetrics getInstance() {
    return INSTANCE;
  }

  public enum Metric {
    DROPPED_QUEUE_OVERFLOW("flagevaluation.rows.dropped", "queue_overflow"),
    DROPPED_CLOSED("flagevaluation.rows.dropped", "closed"),
    DROPPED_DEGRADED_CAP("flagevaluation.rows.dropped", "degraded_cap"),
    DROPPED_PAYLOAD_LIMIT("flagevaluation.rows.dropped", "payload_limit"),
    DEGRADED_CARDINALITY_CAP("flagevaluation.rows.degraded", "cardinality_cap"),
    DEGRADED_PAYLOAD_LIMIT("flagevaluation.rows.degraded", "payload_limit"),
    PAYLOAD_SPLITS("flagevaluation.payload.splits", null);

    private final String name;
    private final String tag;

    Metric(String name, String reason) {
      this.name = name;
      this.tag = reason == null ? null : "reason:" + reason;
    }
  }

  public void count(Metric metric, long value) {
    if (value > 0) {
      counts.add(metric, value);
    }
  }

  /** Records the hook's canonical, comma-separated combination of truncation reasons. */
  public void countContextTruncated(String reason, long value) {
    if (value > 0) {
      contextTruncations.computeIfAbsent(reason, key -> new AtomicLong()).addAndGet(value);
    }
  }

  /** Returns nonzero deltas, resetting only the counts included in this snapshot. */
  public List<Count> drain() {
    List<Count> drained = new ArrayList<>();
    Accumulator.Counts<Metric> snapshot = counts.accumulateAndReset();
    for (Metric metric : snapshot.keys()) {
      long value = snapshot.get(metric);
      if (value > 0) {
        drained.add(new Count(metric.name, metric.tag, value));
      }
    }
    for (Map.Entry<String, AtomicLong> entry : contextTruncations.entrySet()) {
      long value = entry.getValue().getAndSet(0);
      if (value > 0) {
        drained.add(
            new Count("flagevaluation.context.truncated", "reason:" + entry.getKey(), value));
      }
    }
    return drained;
  }

  /** A counter delta with its metric name and optional reason tag, independent of transport. */
  public static final class Count {
    public final String name;
    public final String tag;
    public final long value;

    private Count(String name, String tag, long value) {
      this.name = name;
      this.tag = tag;
      this.value = value;
    }
  }
}
