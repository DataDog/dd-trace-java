package datadog.trace.civisibility.telemetry;

import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricData;
import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilityMetricCollectorImpl implements CiVisibilityMetricCollector {

  private static final Logger log = LoggerFactory.getLogger(CiVisibilityMetricCollectorImpl.class);

  private static final int COUNTER_CARD_SIZE = 64;

  private final BlockingQueue<CiVisibilityMetricData> rawMetricsQueue;
  private final BlockingQueue<DistributionSeriesPoint> rawDistributionPointsQueue;
  private final AtomicLongArray counters;

  /**
   * Cards are used to avoid iterating over the entire {@link
   * CiVisibilityMetricCollectorImpl#counters} array every time {@link
   * CiVisibilityMetricCollectorImpl#prepareMetrics} is called.
   *
   * <p>Every card corresponds to {@link CiVisibilityMetricCollectorImpl#COUNTER_CARD_SIZE} elements
   * of the counters array. If a card is "dirty" (set to {@code true}), it means one or more of the
   * counters that it "covers" was modified. If it is not, then none of the corresponding counters
   * were touched, and iterating that part of the array can be skipped.
   */
  private final AtomicBoolean[] counterDirtyCards;

  public CiVisibilityMetricCollectorImpl() {
    this(
        new ArrayBlockingQueue<>(RAW_QUEUE_SIZE),
        new ArrayBlockingQueue<>(RAW_QUEUE_SIZE),
        CiVisibilityCountMetric.count());
  }

  CiVisibilityMetricCollectorImpl(
      final BlockingQueue<CiVisibilityMetricData> rawMetricsQueue,
      final BlockingQueue<DistributionSeriesPoint> rawDistributionPointsQueue,
      final int countersTotal) {
    this.rawMetricsQueue = rawMetricsQueue;
    this.rawDistributionPointsQueue = rawDistributionPointsQueue;
    this.counters = new AtomicLongArray(countersTotal);

    counterDirtyCards = new AtomicBoolean[(countersTotal - 1) / COUNTER_CARD_SIZE + 1];
    for (int i = 0; i < counterDirtyCards.length; i++) {
      counterDirtyCards[i] = new AtomicBoolean(false);
    }
  }

  @Override
  public void add(CiVisibilityDistributionMetric metric, int value, TagValue... tags) {
    DistributionSeriesPoint point = metric.createDataPoint(value, tags);
    if (!rawDistributionPointsQueue.offer(point)) {
      log.debug(
          "Discarding metric {}:{}:{} because the queue is full",
          metric,
          value,
          Arrays.toString(tags));
    }
  }

  @Override
  public Collection<DistributionSeriesPoint> drainDistributionSeries() {
    if (!this.rawDistributionPointsQueue.isEmpty()) {
      List<DistributionSeriesPoint> drained =
          new ArrayList<>(this.rawDistributionPointsQueue.size());
      this.rawDistributionPointsQueue.drainTo(drained);
      return drained;
    }
    return Collections.emptyList();
  }

  @Override
  public void add(CiVisibilityCountMetric metric, long value, TagValue... tags) {
    int counterIdx = metric.getIndex(tags);
    counters.getAndAdd(counterIdx, value);

    int dirtyCardIdx = counterIdx / COUNTER_CARD_SIZE;
    counterDirtyCards[dirtyCardIdx].set(true);
  }

  @Override
  public void prepareMetrics() {
    int metricIdx = 0;
    CiVisibilityCountMetric[] countMetrics = CiVisibilityCountMetric.values();

    for (int dirtyCardIdx = 0; dirtyCardIdx < counterDirtyCards.length; dirtyCardIdx++) {
      boolean dirty = counterDirtyCards[dirtyCardIdx].getAndSet(false);
      if (!dirty) {
        // none of the counters in this card was touched
        continue;
      }

      int beginIdx = dirtyCardIdx * COUNTER_CARD_SIZE;
      int endIdx = Math.min(beginIdx + COUNTER_CARD_SIZE, counters.length());
      for (int counterIdx = beginIdx; counterIdx < endIdx; counterIdx++) {
        while (countMetrics[metricIdx].getEndIndex() <= counterIdx) {
          metricIdx++;
        }

        long counter = counters.getAndSet(counterIdx, 0);
        if (counter == 0) {
          continue;
        }

        CiVisibilityCountMetric metric = countMetrics[metricIdx];
        TagValue[] tagValues = metric.getTagValues(counterIdx);
        CiVisibilityMetricData metricData = metric.createData(counter, tagValues);
        if (!rawMetricsQueue.offer(metricData)) {
          // re-updating the counter to avoid losing metric data
          add(metric, counter, tagValues);
          return;
        }
      }
    }
  }

  @Override
  public Collection<CiVisibilityMetricData> drain() {
    if (!this.rawMetricsQueue.isEmpty()) {
      List<CiVisibilityMetricData> drained = new ArrayList<>(this.rawMetricsQueue.size());
      this.rawMetricsQueue.drainTo(drained);
      return drained;
    }
    return Collections.emptyList();
  }
}
