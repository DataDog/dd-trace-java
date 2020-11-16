package datadog.trace.common.metrics;

import static datadog.trace.util.AgentThreadFactory.AgentThread.METRICS_AGGREGATOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.LRUCache;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MpmcArrayQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

@Slf4j
public final class ConflatingMetricsAggregator implements MetricsAggregator, EventListener {

  private static final Integer ZERO = 0;

  private static final Batch POISON_PILL = Batch.NULL;

  private final Queue<Batch> batchPool;
  private final ConcurrentHashMap<MetricKey, Batch> pending;
  private final Thread thread;
  private final BlockingQueue<Batch> inbox;
  private final Sink sink;
  private final Aggregator aggregator;

  private volatile boolean enabled = true;

  public ConflatingMetricsAggregator(Config config) {
    this(
        config.getWellKnownTags(),
        new OkHttpSink(config.getAgentUrl(), config.getAgentTimeout()),
        config.getTracerMetricsMaxAggregates(),
        config.getTracerMetricsMaxPending());
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags, Sink sink, int maxAggregates, int queueSize) {
    this(wellKnownTags, sink, maxAggregates, queueSize, 10, SECONDS);
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags,
      Sink sink,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this(
        sink,
        new SerializingMetricWriter(wellKnownTags, sink),
        maxAggregates,
        queueSize,
        reportingInterval,
        timeUnit);
  }

  ConflatingMetricsAggregator(
      Sink sink,
      MetricWriter metricWriter,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this.inbox = new MpscBlockingConsumerArrayQueue<>(queueSize);
    this.batchPool = new MpmcArrayQueue<>(maxAggregates);
    this.pending = new ConcurrentHashMap<>(maxAggregates * 4 / 3, 0.75f);
    this.sink = sink;
    this.aggregator =
        new Aggregator(
            metricWriter, batchPool, inbox, pending, maxAggregates, reportingInterval, timeUnit);
    this.thread = newAgentThread(METRICS_AGGREGATOR, aggregator);
  }

  @Override
  public void start() {
    sink.register(this);
    thread.start();
  }

  @Override
  public void publish(List<? extends CoreSpan<?>> trace) {
    if (enabled) {
      for (CoreSpan<?> span : trace) {
        if (span.isMeasured()) {
          publish(span);
        }
      }
    }
  }

  private void publish(CoreSpan<?> span) {
    MetricKey key =
        new MetricKey(
            span.getResourceName(),
            span.getServiceName(),
            span.getOperationName(),
            span.getType(),
            span.getTag(Tags.DB_TYPE, (CharSequence) ""),
            span.getTag(Tags.HTTP_STATUS, ZERO));
    boolean error = span.getError() > 0;
    long durationNanos = span.getDurationNano();
    Batch batch = pending.get(key);
    if (null != batch) {
      // there is a pending batch, try to win the race to add to it
      // returning false means that either the batch can't take any
      // more data, or it has already been consumed
      if (batch.add(error, durationNanos)) {
        // added to a pending batch prior to consumption
        // so skip publishing to the queue
        return;
      }
    }
    batch = newBatch(key);
    batch.addExclusive(error, durationNanos);
    // overwrite the last one if present, it was already full
    // or had been consumed by the time we tried to add to it
    pending.put(key, batch);
    // must offer to the queue after adding to pending
    inbox.offer(batch);
  }

  private Batch newBatch(MetricKey key) {
    Batch batch = batchPool.poll();
    return (null == batch ? new Batch() : batch).withKey(key);
  }

  public void stop() {
    inbox.offer(POISON_PILL);
  }

  @Override
  public void close() {
    stop();
  }

  @Override
  public void onEvent(EventType eventType, String message) {
    switch (eventType) {
      case DOWNGRADED:
        log.debug("Disabling metric reporting because an agent downgrade was detected");
        disable();
        break;
      case BAD_PAYLOAD:
        log.debug("bad metrics payload sent to trace agent: {}", message);
        break;
      case ERROR:
        log.debug("trace agent errored receiving metrics payload: {}", message);
        break;
      default:
    }
  }

  private void disable() {
    this.enabled = false;
    this.thread.interrupt();
    this.pending.clear();
    this.batchPool.clear();
    this.inbox.clear();
    this.aggregator.clearAggregates();
  }

  private static final class Aggregator implements Runnable {

    private final Queue<Batch> batchPool;
    private final BlockingQueue<Batch> inbox;
    private final LRUCache<MetricKey, AggregateMetric> aggregates;
    private final ConcurrentHashMap<MetricKey, Batch> pending;
    private final MetricWriter writer;
    private final long reportingIntervalNanos;

    private long wallClockTime = -1;

    private long lastReportTime = -1;

    private Aggregator(
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
      this.aggregates = new LRUCache<>(maxAggregates, 0.75f, maxAggregates * 4 / 3);
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
            return;
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
}
