package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.core.util.LRUCache;
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

  private long wallClockTime = -1;

  private long lastReportTime = -1;

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
        Batch batch = inbox.poll(100, MILLISECONDS);
        if (batch == ConflatingMetricsAggregator.POISON_PILL) {
          report(wallClockTime());
          return;
        } else if (null != batch) {
          MetricKey key = batch.getKey();
          // important that it is still *this* batch pending, must not remove otherwise
          pending.remove(key, batch);
          AggregateMetric aggregate = aggregates.get(key);
          if (null == aggregate) {
            aggregate = new AggregateMetric();
            aggregates.put(key, aggregate);
          }
          batch.contributeTo(aggregate);
          // return the batch for reuse
          batchPool.offer(batch);
          reportIfNecessary();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void reportIfNecessary() {
    if (lastReportTime == -1) {
      lastReportTime = System.nanoTime();
      wallClockTime = wallClockTime();
    } else if (!aggregates.isEmpty()) {
      long now = System.nanoTime();
      long delta = now - lastReportTime;
      if (delta > reportingIntervalNanos) {
        report(wallClockTime + delta);
        lastReportTime = now;
        wallClockTime = wallClockTime();
      }
    }
  }

  private void report(long when) {
    writer.startBucket(aggregates.size(), when, reportingIntervalNanos);
    for (Map.Entry<MetricKey, AggregateMetric> aggregate : aggregates.entrySet()) {
      writer.add(aggregate.getKey(), aggregate.getValue());
      aggregate.getValue().clear();
    }
    // note that this may do IO and block
    writer.finishBucket();
  }

  private long wallClockTime() {
    return MILLISECONDS.toNanos(System.currentTimeMillis());
  }
}
