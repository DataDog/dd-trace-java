package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V6_METRICS_ENDPOINT;
import static datadog.trace.api.DDTags.BASE_SERVICE;
import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_INTERNAL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateMetric.TOP_LEVEL_TAG;
import static datadog.trace.common.metrics.SignalItem.ReportSignal.REPORT;
import static datadog.trace.common.metrics.SignalItem.StopSignal.STOP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.METRICS_AGGREGATOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.Collections.unmodifiableSet;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.metrics.SignalItem.ReportSignal;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.queue.BaseQueue;
import datadog.trace.util.queue.Queues;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConflatingMetricsAggregator implements MetricsAggregator, EventListener {

  private static final Logger log = LoggerFactory.getLogger(ConflatingMetricsAggregator.class);

  private static final Map<String, String> DEFAULT_HEADERS =
      Collections.singletonMap(DDAgentApi.DATADOG_META_TRACER_VERSION, DDTraceCoreInfo.VERSION);

  private static final DDCache<String, UTF8BytesString> SERVICE_NAMES =
      DDCaches.newFixedSizeCache(32);

  private static final DDCache<CharSequence, UTF8BytesString> SPAN_KINDS =
      DDCaches.newFixedSizeCache(16);
  private static final DDCache<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      PEER_TAGS_CACHE =
          DDCaches.newFixedSizeCache(
              64); // it can be unbounded since those values are returned by the agent and should be
  // under control. 64 entries is enough in this case to contain all the peer tags.
  private static final Function<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      PEER_TAGS_CACHE_ADDER =
          key ->
              Pair.of(
                  DDCaches.newFixedSizeCache(512),
                  value -> UTF8BytesString.create(key + ":" + value));
  private static final CharSequence SYNTHETICS_ORIGIN = "synthetics";

  private static final Set<String> ELIGIBLE_SPAN_KINDS_FOR_METRICS =
      unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  SPAN_KIND_SERVER, SPAN_KIND_CLIENT, SPAN_KIND_CONSUMER, SPAN_KIND_PRODUCER)));

  private static final Set<String> ELIGIBLE_SPAN_KINDS_FOR_PEER_AGGREGATION =
      unmodifiableSet(
          new HashSet<>(Arrays.asList(SPAN_KIND_CLIENT, SPAN_KIND_PRODUCER, SPAN_KIND_CONSUMER)));

  private final Set<String> ignoredResources;
  private final Queue<Batch> batchPool;
  private final ConcurrentHashMap<MetricKey, Batch> pending;
  private final ConcurrentHashMap<MetricKey, MetricKey> keys;
  private final Thread thread;
  private final BaseQueue<InboxItem> inbox;
  private final Sink sink;
  private final Aggregator aggregator;
  private final long reportingInterval;
  private final TimeUnit reportingIntervalTimeUnit;
  private final DDAgentFeaturesDiscovery features;
  private final HealthMetrics healthMetrics;

  private volatile AgentTaskScheduler.Scheduled<?> cancellation;

  public ConflatingMetricsAggregator(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      HealthMetrics healthMetrics) {
    this(
        config.getWellKnownTags(),
        config.getMetricsIgnoredResources(),
        sharedCommunicationObjects.featuresDiscovery(config),
        healthMetrics,
        new OkHttpSink(
            sharedCommunicationObjects.agentHttpClient,
            sharedCommunicationObjects.agentUrl.toString(),
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
      HealthMetrics healthMetric,
      Sink sink,
      int maxAggregates,
      int queueSize) {
    this(
        wellKnownTags,
        ignoredResources,
        features,
        healthMetric,
        sink,
        maxAggregates,
        queueSize,
        10,
        SECONDS);
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
      DDAgentFeaturesDiscovery features,
      HealthMetrics healthMetric,
      Sink sink,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this(
        ignoredResources,
        features,
        healthMetric,
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
      HealthMetrics healthMetric,
      Sink sink,
      MetricWriter metricWriter,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this.ignoredResources = ignoredResources;
    this.inbox = Queues.mpscArrayQueue(queueSize);
    this.batchPool = Queues.spmcArrayQueue(maxAggregates);
    this.pending = new ConcurrentHashMap<>(maxAggregates * 4 / 3);
    this.keys = new ConcurrentHashMap<>();
    this.features = features;
    this.healthMetrics = healthMetric;
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
    sink.register(this);
    thread.start();
    cancellation =
        AgentTaskScheduler.get()
            .scheduleAtFixedRate(
                new ReportTask(),
                this,
                reportingInterval,
                reportingInterval,
                reportingIntervalTimeUnit);
    log.debug("started metrics aggregator");
  }

  private boolean isMetricsEnabled() {
    if (features.getMetricsEndpoint() == null) {
      features.discoverIfOutdated();
    }
    return features.supportsMetrics();
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
  public Future<Boolean> forceReport() {
    // Ensure the feature is enabled
    if (!isMetricsEnabled()) {
      return CompletableFuture.completedFuture(false);
    }
    // Wait for the thread to start
    while (cancellation == null || (cancellation.get() != null && !thread.isAlive())) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        return CompletableFuture.completedFuture(false);
      }
    }
    // Try to send the report signal
    ReportSignal reportSignal = new ReportSignal();
    boolean published = false;
    while (thread.isAlive() && !published) {
      published = inbox.offer(reportSignal);
      if (!published) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          log.debug("Failed to ask for report");
          break;
        }
      }
    }
    if (published) {
      return reportSignal.future;
    } else {
      return CompletableFuture.completedFuture(false);
    }
  }

  @Override
  public boolean publish(List<? extends CoreSpan<?>> trace) {
    boolean forceKeep = false;
    int counted = 0;
    if (features.supportsMetrics()) {
      for (CoreSpan<?> span : trace) {
        boolean isTopLevel = span.isTopLevel();
        final CharSequence spanKind = span.unsafeGetTag(SPAN_KIND, "");
        if (shouldComputeMetric(span, spanKind)) {
          final CharSequence resourceName = span.getResourceName();
          if (resourceName != null && ignoredResources.contains(resourceName.toString())) {
            // skip publishing all children
            forceKeep = false;
            break;
          }
          counted++;
          forceKeep |= publish(span, isTopLevel, spanKind);
        }
      }
      healthMetrics.onClientStatTraceComputed(
          counted, trace.size(), features.supportsDropping() && !forceKeep);
    }
    return forceKeep;
  }

  private boolean shouldComputeMetric(CoreSpan<?> span, @Nonnull CharSequence spanKind) {
    return (span.isMeasured() || span.isTopLevel() || spanKindEligible(spanKind))
        && span.getLongRunningVersion()
            <= 0 // either not long-running or unpublished long-running span
        && span.getDurationNano() > 0;
  }

  private boolean spanKindEligible(@Nonnull CharSequence spanKind) {
    // use toString since it could be a CharSequence...
    return ELIGIBLE_SPAN_KINDS_FOR_METRICS.contains(spanKind.toString());
  }

  private boolean publish(CoreSpan<?> span, boolean isTopLevel, CharSequence spanKind) {
    MetricKey newKey =
        new MetricKey(
            span.getResourceName(),
            SERVICE_NAMES.computeIfAbsent(span.getServiceName(), UTF8_ENCODE),
            span.getOperationName(),
            span.getType(),
            span.getHttpStatusCode(),
            isSynthetic(span),
            span.getParentId() == 0,
            SPAN_KINDS.computeIfAbsent(
                spanKind, UTF8BytesString::create), // save repeated utf8 conversions
            getPeerTags(span, spanKind.toString()));
    MetricKey key = keys.putIfAbsent(newKey, newKey);
    if (null == key) {
      key = newKey;
    }
    long tag = (span.getError() > 0 ? ERROR_TAG : 0L) | (isTopLevel ? TOP_LEVEL_TAG : 0L);
    long durationNanos = span.getDurationNano();
    Batch batch = pending.get(key);
    if (null != batch) {
      // there is a pending batch, try to win the race to add to it
      // returning false means that either the batch can't take any
      // more data, or it has already been consumed
      if (batch.add(tag, durationNanos)) {
        // added to a pending batch prior to consumption,
        // so skip publishing to the queue (we also know
        // the key isn't rare enough to override the sampler)
        return false;
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
    // force keep keys if there are errors
    return span.getError() > 0;
  }

  private List<UTF8BytesString> getPeerTags(CoreSpan<?> span, String spanKind) {
    if (ELIGIBLE_SPAN_KINDS_FOR_PEER_AGGREGATION.contains(spanKind)) {
      final Set<String> eligiblePeerTags = features.peerTags();
      List<UTF8BytesString> peerTags = new ArrayList<>(eligiblePeerTags.size());
      for (String peerTag : eligiblePeerTags) {
        Object value = span.unsafeGetTag(peerTag);
        if (value != null) {
          final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
              cacheAndCreator = PEER_TAGS_CACHE.computeIfAbsent(peerTag, PEER_TAGS_CACHE_ADDER);
          peerTags.add(
              cacheAndCreator
                  .getLeft()
                  .computeIfAbsent(value.toString(), cacheAndCreator.getRight()));
        }
      }
      return peerTags;
    } else if (SPAN_KIND_INTERNAL.equals(spanKind)) {
      // in this case only the base service should be aggregated if present
      final Object baseService = span.unsafeGetTag(BASE_SERVICE);
      if (baseService != null) {
        final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
            cacheAndCreator = PEER_TAGS_CACHE.computeIfAbsent(BASE_SERVICE, PEER_TAGS_CACHE_ADDER);
        return Collections.singletonList(
            cacheAndCreator
                .getLeft()
                .computeIfAbsent(baseService.toString(), cacheAndCreator.getRight()));
      }
    }
    return Collections.emptyList();
  }

  private static boolean isSynthetic(CoreSpan<?> span) {
    return span.getOrigin() != null && SYNTHETICS_ORIGIN.equals(span.getOrigin().toString());
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
    inbox.offer(STOP);
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
    healthMetrics.onClientStatPayloadSent();
    switch (eventType) {
      case DOWNGRADED:
        log.debug("Agent downgrade was detected");
        disable();
        healthMetrics.onClientStatDowngraded();
        break;
      case BAD_PAYLOAD:
        log.debug("bad metrics payload sent to trace agent: {}", message);
        healthMetrics.onClientStatErrorReceived();
        break;
      case ERROR:
        log.debug("trace agent errored receiving metrics payload: {}", message);
        healthMetrics.onClientStatErrorReceived();
        break;
      default:
        break;
    }
  }

  private void disable() {
    features.discover();
    if (!features.supportsMetrics()) {
      log.debug("Disabling metric reporting because an agent downgrade was detected");
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
