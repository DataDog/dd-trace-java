package datadog.telemetry.metric;

import static java.util.Collections.emptyIterator;
import static java.util.Collections.emptyList;

import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics.Count;
import datadog.trace.api.telemetry.MetricCollector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/** Adapts product-owned flag evaluation counters to telemetry on its collection schedule. */
public final class FlagEvaluationMetricPeriodicAction extends MetricPeriodicAction {
  private final Collector collector = new Collector();

  @Override
  public MetricCollector<MetricCollector.Metric> collector() {
    return collector;
  }

  private static final class Collector implements MetricCollector<MetricCollector.Metric> {
    private final FlagEvaluationMetrics metrics = FlagEvaluationMetrics.getInstance();
    private final ArrayBlockingQueue<Metric> queue = new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
    private Iterator<Count> pending = emptyIterator();

    @Override
    public void prepareMetrics() {
      if (queue.remainingCapacity() == 0) {
        return;
      }
      if (!pending.hasNext()) {
        pending = metrics.drain().iterator();
      }
      // Only the telemetry thread prepares metrics. Retain the rest of a snapshot when full;
      // producer updates remain in the counters until the next snapshot can be collected.
      while (queue.remainingCapacity() > 0 && pending.hasNext()) {
        Count count = pending.next();
        queue.offer(new Metric("tracers", true, count.name, "count", count.value, count.tag));
      }
    }

    @Override
    public Collection<Metric> drain() {
      if (queue.isEmpty()) {
        return emptyList();
      }
      List<Metric> drained = new ArrayList<>(queue.size());
      queue.drainTo(drained);
      return drained;
    }
  }
}
