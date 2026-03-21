package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateMetric.TOP_LEVEL_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AggregateMetricTest {

  @BeforeAll
  static void setupSpec() {
    // Initialize AgentMeter with monitoring - this is the standard mechanism used in production
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY);
    // Create a timer to trigger DDSketchHistograms loading and Factory registration
    monitoring.newTimer("test.init");
  }

  @Test
  void recordDurationsSumsUpToTotal() {
    AggregateMetric aggregate = new AggregateMetric();
    aggregate.recordDurations(3, new AtomicLongArray(new long[] {1, 2, 3}));
    assertEquals(6, aggregate.getDuration());
  }

  @Test
  void totalDurationsIncludeErrors() {
    AggregateMetric aggregate = new AggregateMetric();
    aggregate.recordDurations(3, new AtomicLongArray(new long[] {1, 2, 3}));
    assertEquals(6, aggregate.getDuration());
  }

  @Test
  void clear() {
    AggregateMetric aggregate =
        new AggregateMetric()
            .recordDurations(
                3, new AtomicLongArray(new long[] {5, ERROR_TAG | 6, TOP_LEVEL_TAG | 7}));
    aggregate.clear();
    assertEquals(0, aggregate.getDuration());
    assertEquals(0, aggregate.getErrorCount());
    assertEquals(0, aggregate.getTopLevelCount());
    assertEquals(0, aggregate.getHitCount());
  }

  @Test
  void contributeBatchWithKeyToAggregate() {
    AggregateMetric aggregate =
        new AggregateMetric()
            .recordDurations(
                3, new AtomicLongArray(new long[] {0L, 0L, 0L | ERROR_TAG | TOP_LEVEL_TAG}));

    Batch batch =
        new Batch()
            .reset(
                new MetricKey(
                    "foo",
                    "bar",
                    "qux",
                    null,
                    "type",
                    0,
                    false,
                    true,
                    "corge",
                    Arrays.asList(UTF8BytesString.create("grault:quux")),
                    null,
                    null,
                    null));
    batch.add(0L, 10);
    batch.add(0L, 10);
    batch.add(0L, 10);

    batch.contributeTo(aggregate);

    assertTrue(batch.isUsed());
    assertEquals(30, aggregate.getDuration());
    assertEquals(6, aggregate.getHitCount());
    assertEquals(1, aggregate.getErrorCount());
    assertEquals(1, aggregate.getTopLevelCount());
  }

  @Test
  void ignoreUsedBatches() {
    AggregateMetric aggregate =
        new AggregateMetric()
            .recordDurations(
                10,
                new AtomicLongArray(
                    new long[] {
                      1L, 1L, 1L, 1L, 1L, 1L, 1L | TOP_LEVEL_TAG, 1L, 1L, 1L | ERROR_TAG
                    }));

    Batch batch = new Batch();
    batch.contributeTo(aggregate);
    // must be used now
    batch.add(0L, 10);

    batch.contributeTo(aggregate);

    // batch ignored
    assertEquals(10, aggregate.getDuration());
    assertEquals(10, aggregate.getHitCount());
    assertEquals(1, aggregate.getErrorCount());
    assertEquals(1, aggregate.getTopLevelCount());
  }

  @Test
  void ignoreTrailingZeros() {
    AggregateMetric aggregate = new AggregateMetric();
    aggregate.recordDurations(3, new AtomicLongArray(new long[] {1, 2, 3, 0, 0, 0}));
    assertEquals(6, aggregate.getDuration());
    assertEquals(3, aggregate.getHitCount());
    assertEquals(0, aggregate.getErrorCount());
  }

  @Test
  void hitCountIncludesErrors() {
    AggregateMetric aggregate = new AggregateMetric();
    aggregate.recordDurations(3, new AtomicLongArray(new long[] {1, 2, 3 | ERROR_TAG}));
    assertEquals(3, aggregate.getHitCount());
    assertEquals(1, aggregate.getErrorCount());
  }

  @Test
  void okAndErrorDurationsTrackedSeparately() {
    AggregateMetric aggregate = new AggregateMetric();
    aggregate.recordDurations(
        10,
        new AtomicLongArray(
            new long[] {
              1, 100 | ERROR_TAG, 2, 99 | ERROR_TAG, 3, 98 | ERROR_TAG, 4, 97 | ERROR_TAG
            }));
    assertTrue(aggregate.getErrorLatencies().getMaxValue() >= 99);
    assertTrue(aggregate.getOkLatencies().getMaxValue() <= 5);
  }

  @Test
  void consistentUnderConcurrentAttemptsToReadAndWrite() throws Exception {
    AggregateMetric aggregate = new AggregateMetric();
    MetricKey key =
        new MetricKey(
            "foo",
            "bar",
            "qux",
            null,
            "type",
            0,
            false,
            true,
            "corge",
            Arrays.asList(UTF8BytesString.create("grault:quux")),
            null,
            null,
            null);
    BlockingDeque<Batch> queue = new LinkedBlockingDeque<>(1000);
    ExecutorService reader = Executors.newSingleThreadExecutor();
    int writerCount = 10;
    ExecutorService writers = Executors.newFixedThreadPool(writerCount);
    CountDownLatch readerLatch = new CountDownLatch(1);
    CountDownLatch writerLatch = new CountDownLatch(writerCount);
    CountDownLatch queueEmptyLatch = new CountDownLatch(1);

    AtomicInteger written = new AtomicInteger(0);

    for (int i = 0; i < writerCount; ++i) {
      writers.submit(
          () -> {
            try {
              readerLatch.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            for (int j = 0; j < 10_000; ++j) {
              Batch batch = queue.peekLast();
              if (batch != null && batch.add(0L, 1)) {
                written.incrementAndGet();
              } else {
                queue.offer(new Batch().reset(key));
              }
            }
            writerLatch.countDown();
          });
    }
    Future<?> future =
        reader.submit(
            () -> {
              readerLatch.countDown();
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Batch batch = queue.poll(100, TimeUnit.MILLISECONDS);
                  if (null == batch && writerLatch.getCount() == 0) {
                    queueEmptyLatch.countDown();
                  } else if (null != batch) {
                    batch.contributeTo(aggregate);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });

    assertTrue(writerLatch.await(10, TimeUnit.SECONDS));
    // Wait here until we know that the queue is empty
    assertTrue(queueEmptyLatch.await(10, TimeUnit.SECONDS));
    future.cancel(true);

    assertEquals(written.get(), aggregate.getHitCount());
  }
}
