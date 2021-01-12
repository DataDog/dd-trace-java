package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG;
import static datadog.trace.util.AgentThreadFactory.AgentThread.METRICS_AGGREGATOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import java.util.List;
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

  static final Batch POISON_PILL = Batch.NULL;

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
        new OkHttpSink(
            config.getAgentUrl(),
            config.getAgentTimeout(),
            config.isTracerMetricsBufferingEnabled()),
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
        if (span.isTopLevel() || span.isMeasured()) {
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
            span.getTag(Tags.HTTP_STATUS, ZERO));
    long tag = span.getError() > 0 ? ERROR_TAG : 0L;
    long durationNanos = span.getDurationNano();
    Batch batch = pending.get(key);
    if (null != batch) {
      // there is a pending batch, try to win the race to add to it
      // returning false means that either the batch can't take any
      // more data, or it has already been consumed
      if (batch.add(tag, durationNanos)) {
        // added to a pending batch prior to consumption
        // so skip publishing to the queue
        return;
      }
      // recycle the older key
      key = batch.getKey();
    }
    batch = newBatch(key);
    batch.add(tag, durationNanos);
    // overwrite the last one if present, it was already full
    // or had been consumed by the time we tried to add to it
    pending.put(key, batch);
    // must offer to the queue after adding to pending
    inbox.offer(batch);
  }

  private Batch newBatch(MetricKey key) {
    Batch batch = batchPool.poll();
    if (null == batch) {
      return new Batch(key);
    }
    return batch.reset(key);
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
}
