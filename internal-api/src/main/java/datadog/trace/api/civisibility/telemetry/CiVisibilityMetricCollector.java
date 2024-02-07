package datadog.trace.api.civisibility.telemetry;

import datadog.trace.api.telemetry.MetricCollector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

public class CiVisibilityMetricCollector implements MetricCollector<CiVisibilityMetricData> {

  private static final int COUNTER_CARD_SIZE = 64;
  private static final CiVisibilityMetricCollector INSTANCE = new CiVisibilityMetricCollector();

  public static CiVisibilityMetricCollector getInstance() {
    return INSTANCE;
  }

  private final BlockingQueue<CiVisibilityMetricData> rawMetricsQueue;
  private final AtomicLongArray counters;
  /**
   * Cards are used to avoid iterating over the entire {@link CiVisibilityMetricCollector#counters}
   * array every time {@link CiVisibilityMetricCollector#prepareMetrics} is called.
   *
   * <p>Every card corresponds to {@link CiVisibilityMetricCollector#COUNTER_CARD_SIZE} elements of
   * the counters array. If a card is "dirty" (set to {@code true}), it means one or more of the
   * counters that it "covers" was modified. If it is not, then none of the corresponding counters
   * were touched, and iterating that part of the array can be skipped.
   */
  private final AtomicBoolean[] counterDirtyCards;

  CiVisibilityMetricCollector() {
    this(new ArrayBlockingQueue<>(RAW_QUEUE_SIZE), CiVisibilityCountMetric.count());
  }

  CiVisibilityMetricCollector(
      final BlockingQueue<CiVisibilityMetricData> rawMetricsQueue, final int countersTotal) {
    this.rawMetricsQueue = rawMetricsQueue;
    this.counters = new AtomicLongArray(countersTotal);

    counterDirtyCards = new AtomicBoolean[(countersTotal - 1) / COUNTER_CARD_SIZE + 1];
    for (int i = 0; i < counterDirtyCards.length; i++) {
      counterDirtyCards[i] = new AtomicBoolean(false);
    }
  }

  public void add(CiVisibilityDistributionMetric metric, long value, TagValue... tags) {
    CiVisibilityMetricData metricData = metric.createData(value, tags);
    rawMetricsQueue.add(metricData);
  }

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

        TagValue[] tagValues = countMetrics[metricIdx].getTagValues(counterIdx);
        CiVisibilityMetricData metricData = countMetrics[metricIdx].createData(counter, tagValues);
        if (!rawMetricsQueue.offer(metricData)) {
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
