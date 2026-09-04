package datadog.trace.api.telemetry;

import datadog.trace.api.metrics.BaggageMetrics;
import datadog.trace.api.metrics.CoreCounter;
import datadog.trace.api.metrics.SpanMetricRegistryImpl;
import datadog.trace.api.metrics.SpanMetricsImpl;
import datadog.trace.api.metrics.StatsMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** This class is in charge of draining core metrics for telemetry. */
public class CoreMetricCollector implements MetricCollector<CoreMetricCollector.CoreMetric> {
  private static final String METRIC_NAMESPACE = "tracers";
  private static final String INTEGRATION_NAME_TAG = "integration_name:";
  private static final CoreMetricCollector INSTANCE = new CoreMetricCollector();
  private final SpanMetricRegistryImpl spanMetricRegistry = SpanMetricRegistryImpl.getInstance();
  private final BaggageMetrics baggageMetrics = BaggageMetrics.getInstance();
  private final StatsMetrics statsMetrics = StatsMetrics.getInstance();

  private final BlockingQueue<CoreMetric> metricsQueue;

  public static CoreMetricCollector getInstance() {
    return INSTANCE;
  }

  private CoreMetricCollector() {
    this.metricsQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
  }

  public void count(String metricName, long value, String tag) {
    if (value <= 0) {
      return;
    }
    this.metricsQueue.offer(
        new CoreMetric(METRIC_NAMESPACE, true, metricName, "count", value, tag));
  }

  @Override
  public void prepareMetrics() {
    // Collect the bounded, high-value client-side trace-stats span-collapse counters first, tagged
    // by collapse reason. There is only a small, fixed set of these; the span-metric registry below
    // is unbounded (one entry per instrumentation name), so draining it first could fill the queue
    // and starve the collapse counters indefinitely under high instrumentation counts. Collecting
    // them up front guarantees they are emitted.
    for (StatsMetrics.TaggedCounter counter : this.statsMetrics.getTaggedCounters()) {
      if (this.metricsQueue.remainingCapacity() == 0) {
        // Queue full: stop before reading any more counters. getValueAndReset() below resets the
        // counter's delta baseline, so resetting one we then fail to enqueue would drop that delta
        // for good; the untouched counters are picked up on the next collection cycle.
        break;
      }
      long value = counter.getValueAndReset();
      if (value == 0) {
        // Skip not updated counters
        continue;
      }
      CoreMetric metric =
          new CoreMetric(
              METRIC_NAMESPACE, true, counter.getName(), "count", value, counter.getTag());
      if (!this.metricsQueue.offer(metric)) {
        // Stop adding metrics if the queue is full
        break;
      }
    }

    // Collect span metrics
    spanMetricsLoop:
    for (SpanMetricsImpl spanMetrics : this.spanMetricRegistry.getSpanMetrics()) {
      if (this.metricsQueue.remainingCapacity() == 0) {
        // Queue full: stop before touching any more span-metrics entries, not just the counters of
        // the current one, so a full queue doesn't leave us building tags and iterating counters we
        // can't enqueue anyway.
        break;
      }
      String tag = INTEGRATION_NAME_TAG + spanMetrics.getInstrumentationName();
      for (CoreCounter counter : spanMetrics.getCounters()) {
        if (this.metricsQueue.remainingCapacity() == 0) {
          // Queue full: stop before reading any more counters. getValueAndReset() below resets the
          // counter's delta baseline, so resetting one we then fail to enqueue would drop that
          // delta for good; the untouched counters are picked up on the next collection cycle.
          break spanMetricsLoop;
        }
        long value = counter.getValueAndReset();
        if (value == 0) {
          // Skip not updated counters
          continue;
        }
        CoreMetric metric =
            new CoreMetric(METRIC_NAMESPACE, true, counter.getName(), "count", value, tag);
        if (!this.metricsQueue.offer(metric)) {
          // Stop adding metrics if the queue is full
          break spanMetricsLoop;
        }
      }
    }

    // Collect baggage metrics
    for (BaggageMetrics.TaggedCounter counter : this.baggageMetrics.getTaggedCounters()) {
      if (this.metricsQueue.remainingCapacity() == 0) {
        // Queue full: stop before reading any more counters. getValueAndReset() below resets the
        // counter's delta baseline, so resetting one we then fail to enqueue would drop that
        // delta for good; the untouched counters are picked up on the next collection cycle.
        break;
      }
      long value = counter.getValueAndReset();
      if (value == 0) {
        // Skip not updated counters
        continue;
      }
      // Use the specific tag for each baggage metric
      String tag = counter.getTag();
      CoreMetric metric =
          new CoreMetric(METRIC_NAMESPACE, true, counter.getName(), "count", value, tag);
      if (!this.metricsQueue.offer(metric)) {
        // Stop adding metrics if the queue is full
        break;
      }
    }
  }

  @Override
  public Collection<CoreMetric> drain() {
    if (this.metricsQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<CoreMetric> drained = new ArrayList<>(this.metricsQueue.size());
    this.metricsQueue.drainTo(drained);
    return drained;
  }

  public static class CoreMetric extends MetricCollector.Metric {
    public CoreMetric(
        String namespace,
        boolean common,
        String metricName,
        String type,
        Number value,
        String tag) {
      super(namespace, common, metricName, type, value, tag);
    }
  }
}
