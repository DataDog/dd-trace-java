package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.metrics.SignalItem.StopSignal;
import datadog.trace.core.util.LRUCache;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jctools.maps.NonBlockingHashMap;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscCompoundQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Aggregator implements Runnable {

  private static final long DEFAULT_SLEEP_MILLIS = 10;

  private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

  private final Queue<Batch> batchPool;
  private final MpscCompoundQueue<InboxItem> inbox;
  private final LRUCache<MetricKey, AggregateMetric> aggregates;
  private final NonBlockingHashMap<MetricKey, Batch> pending;
  private final Set<MetricKey> commonKeys;
  private final MetricWriter writer;
  // the reporting interval controls how much history will be buffered
  // when the agent is unresponsive (only 10 pending requests will be
  // buffered by OkHttpSink)
  private final long reportingIntervalNanos;

  private final long sleepMillis;

  private boolean dirty;

  Aggregator(
      MetricWriter writer,
      Queue<Batch> batchPool,
      MpscCompoundQueue<InboxItem> inbox,
      NonBlockingHashMap<MetricKey, Batch> pending,
      final Set<MetricKey> commonKeys,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit) {
    this(
        writer,
        batchPool,
        inbox,
        pending,
        commonKeys,
        maxAggregates,
        reportingInterval,
        reportingIntervalTimeUnit,
        DEFAULT_SLEEP_MILLIS);
  }

  Aggregator(
      MetricWriter writer,
      Queue<Batch> batchPool,
      MpscCompoundQueue<InboxItem> inbox,
      NonBlockingHashMap<MetricKey, Batch> pending,
      final Set<MetricKey> commonKeys,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      long sleepMillis) {
    this.writer = writer;
    this.batchPool = batchPool;
    this.inbox = inbox;
    this.commonKeys = commonKeys;
    this.aggregates =
        new LRUCache<>(
            new CommonKeyCleaner(commonKeys), maxAggregates * 4 / 3, 0.75f, maxAggregates);
    this.pending = pending;
    this.reportingIntervalNanos = reportingIntervalTimeUnit.toNanos(reportingInterval);
    this.sleepMillis = sleepMillis;
  }

  public void clearAggregates() {
    this.aggregates.clear();
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    Drainer drainer = new Drainer();
    while (!currentThread.isInterrupted() && !drainer.stopped) {
      try {
        if (!inbox.isEmpty()) {
          inbox.drain(drainer);
        } else {
          Thread.sleep(sleepMillis);
        }
      } catch (InterruptedException e) {
        currentThread.interrupt();
      } catch (Throwable error) {
        log.debug("error aggregating metrics", error);
      }
    }
    log.debug("metrics aggregator exited");
  }

  private final class Drainer implements MessagePassingQueue.Consumer<InboxItem> {

    boolean stopped = false;

    @Override
    public void accept(InboxItem item) {
      if (item instanceof SignalItem) {
        SignalItem signal = (SignalItem) item;
        if (!stopped) {
          report(wallClockTime(), signal);
          stopped = item instanceof StopSignal;
          if (stopped) {
            signal.complete();
          }
        } else {
          signal.ignore();
        }
      } else if (item instanceof Batch && !stopped) {
        final Batch batch = (Batch) item;
        MetricKey key = batch.getKey();
        // important that it is still *this* batch pending, must not remove otherwise
        pending.remove(key, batch);
        // operations concerning the aggregates should be atomic not to potentially loose points.
        aggregates.compute(
            key,
            (k, v) -> {
              if (v == null) {
                v = new AggregateMetric();
              }
              batch.contributeTo(v);
              return v;
            });
        dirty = true;
        // return the batch for reuse
        batchPool.offer(batch);
      }
    }
  }

  private void report(long when, SignalItem signal) {
    boolean skipped = true;
    if (dirty) {
      try {
        final Set<MetricKey> validKeys = expungeStaleAggregates();
        if (!validKeys.isEmpty()) {
          skipped = false;
          writer.startBucket(validKeys.size(), when, reportingIntervalNanos);
          for (MetricKey key : validKeys) {
            // operations concerning the aggregates should be atomic not to potentially loose
            // points.
            aggregates.computeIfPresent(
                key,
                (k, v) -> {
                  writer.add(k, v);
                  v.clear();
                  return v;
                });
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

  /**
   * Remove keys whose values have zeroed metrics.
   *
   * @return a set containing the keys still valid.
   */
  private Set<MetricKey> expungeStaleAggregates() {
    final HashSet<MetricKey> ret = new HashSet<>();
    for (MetricKey metricKey : new HashSet<>(aggregates.keySet())) {
      // operations concerning the aggregates should be atomic not to potentially loose points.
      aggregates.computeIfPresent(
          metricKey,
          (k, v) -> {
            if (v.getHitCount() == 0) {
              commonKeys.remove(k);
              return null;
            }
            ret.add(k);
            return v;
          });
    }
    return ret;
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
