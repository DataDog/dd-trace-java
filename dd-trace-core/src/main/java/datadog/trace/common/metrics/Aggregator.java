package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.metrics.SignalItem.ReportSignal;
import datadog.trace.common.metrics.SignalItem.StopSignal;
import datadog.trace.core.util.LRUCache;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jctools.maps.NonBlockingHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Aggregator implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

  private final Queue<Batch> batchPool;
  private final BlockingQueue<InboxItem> inbox;
  private final LRUCache<MetricKey, AggregateMetric> aggregates;
  private final NonBlockingHashMap<MetricKey, Batch> pending;
  private final Set<MetricKey> commonKeys;
  private final MetricWriter writer;
  // the reporting interval controls how much history will be buffered
  // when the agent is unresponsive (only 10 pending requests will be
  // buffered by OkHttpSink)
  private final long reportingIntervalNanos;

  private boolean dirty;

  Aggregator(
      MetricWriter writer,
      Queue<Batch> batchPool,
      BlockingQueue<InboxItem> inbox,
      NonBlockingHashMap<MetricKey, Batch> pending,
      final Set<MetricKey> commonKeys,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit) {
    this.writer = writer;
    this.batchPool = batchPool;
    this.inbox = inbox;
    this.commonKeys = commonKeys;
    this.aggregates =
        new LRUCache<>(
            new CommonKeyCleaner(commonKeys), maxAggregates * 4 / 3, 0.75f, maxAggregates);
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
        InboxItem item = inbox.take();
        if (item instanceof StopSignal) {
          stop((StopSignal) item);
          break;
        } else if (item instanceof ReportSignal) {
          report(wallClockTime(), (ReportSignal) item);
        } else if (item instanceof Batch) {
          Batch batch = (Batch) item;
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
        currentThread.interrupt();
      } catch (Throwable error) {
        log.debug("error aggregating metrics", error);
      }
    }
    log.debug("metrics aggregator exited");
  }

  private void stop(StopSignal stopSignal) {
    report(wallClockTime(), stopSignal);
    for (InboxItem item : inbox) {
      if (item instanceof SignalItem) {
        ((SignalItem) item).ignore();
      }
    }
    stopSignal.complete();
  }

  private void report(long when, SignalItem signal) {
    boolean skipped = true;
    if (dirty) {
      try {
        expungeStaleAggregates();
        if (!aggregates.isEmpty()) {
          skipped = false;
          writer.startBucket(aggregates.size(), when, reportingIntervalNanos);
          for (Map.Entry<MetricKey, AggregateMetric> aggregate : aggregates.entrySet()) {
            writer.add(aggregate.getKey(), aggregate.getValue());
            aggregate.getValue().clear();
          }
          // note that this may do IO and block
          writer.finishBucket();
        }
      } catch (Throwable error) {
        writer.reset();
        log.debug("Error publishing metrics. Dropping payload", error);
      }
      dirty = false;
    }
    signal.complete();
    if (skipped) {
      log.debug("skipped metrics reporting because no points have changed");
    }
  }

  private void expungeStaleAggregates() {
    Iterator<Map.Entry<MetricKey, AggregateMetric>> it = aggregates.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<MetricKey, AggregateMetric> pair = it.next();
      AggregateMetric metric = pair.getValue();
      if (metric.getHitCount() == 0) {
        it.remove();
        commonKeys.remove(pair.getKey());
      }
    }
  }

  private long wallClockTime() {
    return MILLISECONDS.toNanos(System.currentTimeMillis());
  }

  private static final class CommonKeyCleaner
      implements LRUCache.ExpiryListener<MetricKey, AggregateMetric> {

    private final Set<MetricKey> commonKeys;

    private CommonKeyCleaner(Set<MetricKey> commonKeys) {
      this.commonKeys = commonKeys;
    }

    @Override
    public void accept(Map.Entry<MetricKey, AggregateMetric> expired) {
      commonKeys.remove(expired.getKey());
    }
  }
}
