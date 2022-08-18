package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V6_METRICS_ENDPOINT;
import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateMetric.TOP_LEVEL_TAG;
import static datadog.trace.common.metrics.Batch.REPORT;
import static datadog.trace.util.AgentThreadFactory.AgentThread.METRICS_AGGREGATOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jctools.maps.NonBlockingHashMap;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.jctools.queues.SpmcArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConflatingMetricsAggregator implements MetricsAggregator, EventListener {

  private static final Logger log = LoggerFactory.getLogger(ConflatingMetricsAggregator.class);

  private static final Map<String, String> DEFAULT_HEADERS =
      Collections.singletonMap(DDAgentApi.DATADOG_META_TRACER_VERSION, DDTraceCoreInfo.VERSION);

  private static final DDCache<String, UTF8BytesString> SERVICE_NAMES =
      DDCaches.newFixedSizeCache(32);

  static final Batch POISON_PILL = Batch.NULL;

  private final Set<String> ignoredResources;
  private final Queue<Batch> batchPool;
  private final NonBlockingHashMap<MetricKey, Batch> pending;
  private final NonBlockingHashMap<MetricKey, MetricKey> keys;
  private final Thread thread;
  private final BlockingQueue<Batch> inbox;
  private final Sink sink;
  private final Aggregator aggregator;
  private final long reportingInterval;
  private final TimeUnit reportingIntervalTimeUnit;
  private final DDAgentFeaturesDiscovery features;

  private volatile AgentTaskScheduler.Scheduled<?> cancellation;

  public ConflatingMetricsAggregator(
      Config config, SharedCommunicationObjects sharedCommunicationObjects) {
    this(
        config.getWellKnownTags(),
        config.getMetricsIgnoredResources(),
        sharedCommunicationObjects.featuresDiscovery(config),
        new OkHttpSink(
            sharedCommunicationObjects.okHttpClient,
            config.getAgentUrl(),
            V6_METRICS_ENDPOINT,
            config.isTracerMetricsBufferingEnabled(),
            false,
            DEFAULT_HEADERS),
        config.getTracerMetricsMaxAggregates(),
        config.getTracerMetricsMaxPending());
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
      DDAgentFeaturesDiscovery features,
      Sink sink,
      int maxAggregates,
      int queueSize) {
    this(wellKnownTags, ignoredResources, features, sink, maxAggregates, queueSize, 10, SECONDS);
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
      DDAgentFeaturesDiscovery features,
      Sink sink,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this(
        ignoredResources,
        features,
        sink,
        new SerializingMetricWriter(wellKnownTags, sink),
        maxAggregates,
        queueSize,
        reportingInterval,
        timeUnit);
  }

  ConflatingMetricsAggregator(
      Set<String> ignoredResources,
      DDAgentFeaturesDiscovery features,
      Sink sink,
      MetricWriter metricWriter,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this.ignoredResources = ignoredResources;
    this.inbox = new MpscBlockingConsumerArrayQueue<>(queueSize);
    this.batchPool = new SpmcArrayQueue<>(maxAggregates);
    this.pending = new NonBlockingHashMap<>(maxAggregates * 4 / 3);
    this.keys = new NonBlockingHashMap<>();
    this.features = features;
    this.sink = sink;
    this.aggregator =
        new Aggregator(
            metricWriter,
            batchPool,
            inbox,
            pending,
            keys.keySet(),
            maxAggregates,
            reportingInterval,
            timeUnit);
    this.thread = newAgentThread(METRICS_AGGREGATOR, aggregator);
    this.reportingInterval = reportingInterval;
    this.reportingIntervalTimeUnit = timeUnit;
  }

  @Override
  public void start() {
    if (features.getMetricsEndpoint() == null) {
      features.discover();
    }
    if (features.supportsMetrics()) {
      sink.register(this);
      thread.start();
      cancellation =
          AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
              new ReportTask(),
              this,
              reportingInterval,
              reportingInterval,
              reportingIntervalTimeUnit);
      log.debug("started metrics aggregator");
    } else {
      log.debug("metrics not supported by trace agent");
    }
  }

  @Override
  public boolean report() {
    boolean published;
    int attempts = 0;
    do {
      published = inbox.offer(REPORT);
      ++attempts;
    } while (!published && attempts < 10);
    if (!published) {
      log.debug("Skipped metrics reporting because the queue is full");
    }
    return published;
  }

  @Override
  public boolean publish(List<? extends CoreSpan<?>> trace) {
    boolean forceKeep = false;
    if (features.supportsMetrics()) {
      for (CoreSpan<?> span : trace) {
        boolean isTopLevel = span.isTopLevel();
        if (isTopLevel || span.isMeasured()) {
          if (ignoredResources.contains(span.getResourceName().toString())) {
            // skip publishing all children
            return false;
          }
          forceKeep |= publish(span, isTopLevel);
        }
      }
    }
    return forceKeep;
  }

  private boolean publish(CoreSpan<?> span, boolean isTopLevel) {
    MetricKey newKey =
        new MetricKey(
            span.getResourceName(),
            SERVICE_NAMES.computeIfAbsent(span.getServiceName(), UTF8_ENCODE),
            span.getOperationName(),
            span.getType(),
            span.getHttpStatusCode());
    boolean isNewKey = false;
    MetricKey key = keys.putIfAbsent(newKey, newKey);
    if (null == key) {
      key = newKey;
      isNewKey = true;
    }
    long tag = (span.getError() > 0 ? ERROR_TAG : 0L) | (isTopLevel ? TOP_LEVEL_TAG : 0L);
    long durationNanos = span.getDurationNano();
    Batch batch = pending.get(key);
    if (null != batch) {
      // there is a pending batch, try to win the race to add to it
      // returning false means that either the batch can't take any
      // more data, or it has already been consumed
      if (batch.add(tag, durationNanos)) {
        // added to a pending batch prior to consumption
        // so skip publishing to the queue (we also know
        // the key isn't rare enough to override the sampler)
        return false;
      }
      // recycle the older key
      key = batch.getKey();
      isNewKey = false;
    }
    batch = newBatch(key);
    batch.add(tag, durationNanos);
    // overwrite the last one if present, it was already full
    // or had been consumed by the time we tried to add to it
    pending.put(key, batch);
    // must offer to the queue after adding to pending
    inbox.offer(batch);
    // force keep keys we haven't seen before or errors
    return isNewKey || span.getError() > 0;
  }

  private Batch newBatch(MetricKey key) {
    Batch batch = batchPool.poll();
    if (null == batch) {
      return new Batch(key);
    }
    return batch.reset(key);
  }

  public void stop() {
    if (null != cancellation) {
      cancellation.cancel();
    }
    inbox.offer(POISON_PILL);
  }

  @Override
  public void close() {
    stop();
    try {
      thread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  @Override
  public void onEvent(EventType eventType, String message) {
    switch (eventType) {
      case DOWNGRADED:
        log.debug("Agent downgrade was detected");
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
    features.discover();
    if (!features.supportsMetrics()) {
      log.debug("Disabling metric reporting because an agent downgrade was detected");
      AgentTaskScheduler.Scheduled<?> cancellation = this.cancellation;
      if (null != cancellation) {
        cancellation.cancel();
      }
      this.thread.interrupt();
      this.pending.clear();
      this.batchPool.clear();
      this.inbox.clear();
      this.aggregator.clearAggregates();
    }
  }

  private static final class ReportTask
      implements AgentTaskScheduler.Task<ConflatingMetricsAggregator> {

    @Override
    public void run(ConflatingMetricsAggregator target) {
      target.report();
    }
  }
}
