package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.Batch.REPORT;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.POISON_PILL;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.core.util.LRUCache;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class Aggregator implements Runnable {

  private final Queue<Batch> batchPool;
  private final BlockingQueue<Batch> inbox;
  private final LRUCache<MetricKey, AggregateMetric> aggregates;
  private final ConcurrentHashMap<MetricKey, Batch> pending;
  private final MetricWriter writer;
  // the reporting interval controls how much history will be buffered
  // when the agent is unresponsive (only 10 pending requests will be
  // buffered by OkHttpSink)
  private final long reportingIntervalNanos;

  private boolean dirty;

  Aggregator(
      MetricWriter writer,
      Queue<Batch> batchPool,
      BlockingQueue<Batch> inbox,
      ConcurrentHashMap<MetricKey, Batch> pending,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit) {
    this.writer = writer;
    this.batchPool = batchPool;
    this.inbox = inbox;
    this.aggregates = new LRUCache<>(maxAggregates * 4 / 3, 0.75f, maxAggregates);
    this.pending = pending;
    this.reportingIntervalNanos = reportingIntervalTimeUnit.toNanos(reportingInterval);
  }

  public void clearAggregates() {
    this.aggregates.clear();
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    while (!currentThread.isInterrupted()) {
      try {
        Batch batch = inbox.take();
        if (batch == POISON_PILL) {
          report(wallClockTime());
          break;
        } else if (batch == REPORT) {
          report(wallClockTime());
        } else {
          MetricKey key = batch.getKey();
          // important that it is still *this* batch pending, must not remove otherwise
          pending.remove(key, batch);
          AggregateMetric aggregate = aggregates.get(key);
          if (null == aggregate) {
            aggregate = new AggregateMetric();
            aggregates.put(key, aggregate);
          }
          batch.contributeTo(aggregate);
          dirty = true;
          // return the batch for reuse
          batchPool.offer(batch);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void report(long when) {
    if (dirty) {
      expungeStaleAggregates();
      if (!aggregates.isEmpty()) {
        writer.startBucket(aggregates.size(), when, reportingIntervalNanos);
        for (Map.Entry<MetricKey, AggregateMetric> aggregate : aggregates.entrySet()) {
          if (aggregate.getValue().getHitCount() > 0) {
            writer.add(aggregate.getKey(), aggregate.getValue());
            aggregate.getValue().clear();
          }
        }
        // note that this may do IO and block
        writer.finishBucket();
      }
      dirty = false;
    }
  }

  private void expungeStaleAggregates() {
    Iterator<Map.Entry<MetricKey, AggregateMetric>> it = aggregates.entrySet().iterator();
    while (it.hasNext()) {
      AggregateMetric metric = it.next().getValue();
      if (metric.getHitCount() == 0) {
        it.remove();
      }
    }
  }

  private long wallClockTime() {
    return MILLISECONDS.toNanos(System.currentTimeMillis());
  }
}
