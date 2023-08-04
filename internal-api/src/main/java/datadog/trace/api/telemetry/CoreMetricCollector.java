package datadog.trace.api.telemetry;

import datadog.trace.api.metrics.CoreCounter;
import datadog.trace.api.metrics.SpanMetricRegistryImpl;
import datadog.trace.api.metrics.SpanMetrics;
import datadog.trace.api.metrics.SpanMetricsImpl;
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

  private final BlockingQueue<CoreMetric> metricsQueue;

  public static CoreMetricCollector getInstance() {
    return INSTANCE;
  }

  private CoreMetricCollector() {
    this.metricsQueue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
  }

  @Override
  public void prepareMetrics() {
    for (SpanMetrics spanMetric : this.spanMetricRegistry.getSpanMetrics()) {
      SpanMetricsImpl spanMetricsImpl = (SpanMetricsImpl) spanMetric;
      String tag = INTEGRATION_NAME_TAG + spanMetricsImpl.getInstrumentationName();
      for (CoreCounter counter : spanMetricsImpl.getCounters()) {
        long value = counter.getValueAndReset();
        if (value == 0) {
          // Skip not updated counters
          continue;
        }
        CoreMetric metric =
            new CoreMetric(METRIC_NAMESPACE, true, counter.getName(), "count", value, tag);
        if (!this.metricsQueue.offer(metric)) {
          // Stop adding metrics if the queue is full
          break;
        }
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
