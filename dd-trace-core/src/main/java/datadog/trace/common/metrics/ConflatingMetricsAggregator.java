package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.api.DDSpanTypes.RPC;
import static datadog.trace.api.DDTags.BASE_SERVICE;
import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ENDPOINT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
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

import datadog.common.queue.Queues;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.metrics.SignalItem.ReportSignal;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.jctools.queues.MessagePassingQueue;
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
  private static final DDCache<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      ADDITIONAL_TAG_VALUES_CACHE = DDCaches.newFixedSizeCache(64);
  private static final Function<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      ADDITIONAL_TAG_VALUES_CACHE_ADDER =
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

  // Cap on configured additional metric tag keys. By default only 4 primary tag dimensions are
  // supported.
  // We sometimes increase this limit for users so a value of 10 allows us to protect against
  // extreme misconfiguration
  // while still allowing some additional tags to be used.
  static final int MAX_ADDITIONAL_TAG_KEYS = 10;

  // Maximum length of an additional metric tag *value*. Caps cache footprint and wire payload
  // size from stack-trace / JSON / SQL stuffed into a tag by misconfigured app code. Values
  // exceeding this are emitted as `<tagKey>:blocked_by_tracer`.
  static final int MAX_ADDITIONAL_TAG_VALUE_LENGTH = 250;

  private final Set<String> ignoredResources;
  private final List<String> additionalTagKeys;
  private final AdditionalTagsCardinalityLimiter cardinalityLimiter;
  private final MessagePassingQueue<Batch> batchPool;
  private final ConcurrentHashMap<MetricKey, Batch> pending;
  private final ConcurrentHashMap<MetricKey, MetricKey> keys;
  private final Thread thread;
  private final MessagePassingQueue<InboxItem> inbox;
  private final Sink sink;
  private final Aggregator aggregator;
  private final long reportingInterval;
  private final TimeUnit reportingIntervalTimeUnit;
  private final DDAgentFeaturesDiscovery features;
  private final HealthMetrics healthMetrics;
  private final boolean includeEndpointInMetrics;

  private volatile AgentTaskScheduler.Scheduled<?> cancellation;

  public ConflatingMetricsAggregator(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      HealthMetrics healthMetrics) {
    this(
        config.getWellKnownTags(),
        config.getMetricsIgnoredResources(),
        config.getTraceStatsAdditionalTags(),
        config.getTraceStatsAdditionalTagsCardinalityLimit(),
        sharedCommunicationObjects.featuresDiscovery(config),
        healthMetrics,
        new OkHttpSink(
            sharedCommunicationObjects.agentHttpClient,
            sharedCommunicationObjects.agentUrl.toString(),
            V06_METRICS_ENDPOINT,
            config.isTracerMetricsBufferingEnabled(),
            false,
            DEFAULT_HEADERS),
        config.getTracerMetricsMaxAggregates(),
        config.getTracerMetricsMaxPending(),
        config.isTraceResourceRenamingEnabled());
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
      Set<String> additionalTagKeys,
      int additionalTagsCardinalityLimit,
      DDAgentFeaturesDiscovery features,
      HealthMetrics healthMetric,
      Sink sink,
      int maxAggregates,
      int queueSize,
      boolean includeEndpointInMetrics) {
    this(
        wellKnownTags,
        ignoredResources,
        additionalTagKeys,
        additionalTagsCardinalityLimit,
        features,
        healthMetric,
        sink,
        maxAggregates,
        queueSize,
        10,
        SECONDS,
        includeEndpointInMetrics);
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
      Set<String> additionalTagKeys,
      int additionalTagsCardinalityLimit,
      DDAgentFeaturesDiscovery features,
      HealthMetrics healthMetric,
      Sink sink,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit,
      boolean includeEndpointInMetrics) {
    this(
        ignoredResources,
        additionalTagKeys,
        additionalTagsCardinalityLimit,
        features,
        healthMetric,
        sink,
        new SerializingMetricWriter(wellKnownTags, sink),
        maxAggregates,
        queueSize,
        reportingInterval,
        timeUnit,
        includeEndpointInMetrics);
  }

  ConflatingMetricsAggregator(
      Set<String> ignoredResources,
      Set<String> additionalTagKeys,
      int additionalTagsCardinalityLimit,
      DDAgentFeaturesDiscovery features,
      HealthMetrics healthMetric,
      Sink sink,
      MetricWriter metricWriter,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit,
      boolean includeEndpointInMetrics) {
    this.ignoredResources = ignoredResources;
    this.additionalTagKeys = normalizeAdditionalTagKeys(additionalTagKeys);
    this.cardinalityLimiter =
        new AdditionalTagsCardinalityLimiter(additionalTagsCardinalityLimit, healthMetric);
    this.includeEndpointInMetrics = includeEndpointInMetrics;
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
            timeUnit,
            healthMetric);
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
    cardinalityLimiter.resetBucket();
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
      healthMetrics.onClientStatTraceComputed(counted, trace.size(), !forceKeep);
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
    // Extract HTTP method and endpoint only if the feature is enabled
    String httpMethod = null;
    String httpEndpoint = null;
    if (includeEndpointInMetrics) {
      Object httpMethodObj = span.unsafeGetTag(HTTP_METHOD);
      httpMethod = httpMethodObj != null ? httpMethodObj.toString() : null;
      Object httpEndpointObj = span.unsafeGetTag(HTTP_ENDPOINT);
      httpEndpoint = httpEndpointObj != null ? httpEndpointObj.toString() : null;
    }

    CharSequence spanType = span.getType();
    String grpcStatusCode = null;
    if (spanType != null && RPC.contentEquals(spanType)) {
      Object grpcStatusObj = span.unsafeGetTag(InstrumentationTags.GRPC_STATUS_CODE);
      grpcStatusCode = grpcStatusObj != null ? grpcStatusObj.toString() : null;
    }
    List<UTF8BytesString> fullAdditionalTags = getAdditionalTagsLengthCapped(span);
    MetricKey newKey =
        new MetricKey(
            span.getResourceName(),
            SERVICE_NAMES.computeIfAbsent(span.getServiceName(), UTF8_ENCODE),
            span.getOperationName(),
            span.getServiceNameSource(),
            spanType,
            span.getHttpStatusCode(),
            isSynthetic(span),
            span.getParentId() == 0,
            SPAN_KINDS.computeIfAbsent(
                spanKind, UTF8BytesString::create), // save repeated utf8 conversions
            getPeerTags(span, spanKind.toString()),
            httpMethod,
            httpEndpoint,
            grpcStatusCode,
            fullAdditionalTags);

    // If this span's full MetricKey is already in the current bucket, just admit it as-is.
    // We already paid the cardinality cost for these tag values earlier in this bucket, so the
    // existing entry should keep receiving merges.
    //
    // Otherwise, if the bucket is at the global cap, replace every present tag's value with
    // `blocked_by_tracer` so the dimension keys are preserved on the wire even though the
    // values aren't (blocked spans collapse into one bucket per tag-presence shape rather than
    // into the no-additional-tags base bucket). If it's a new entry under the cap, admit with
    // the full tag set and remember to bump the counter below.
    boolean newEntryUsedFullTags = false;
    if (!fullAdditionalTags.isEmpty() && pending.get(newKey) == null) {
      if (cardinalityLimiter.isAtCap()) {
        cardinalityLimiter.recordCardinalityBlock();
        newKey = rebuildKeyWithBlockedValues(span, newKey);
      } else {
        newEntryUsedFullTags = true;
      }
    }

    MetricKey key = keys.putIfAbsent(newKey, newKey);
    if (null == key) {
      key = newKey;
    }
    long tag = (span.getError() > 0 ? ERROR_TAG : 0L) | (isTopLevel ? TOP_LEVEL_TAG : 0L);
    long durationNanos = span.getDurationNano();
    Batch batch = pending.get(key);
    boolean isNewBucketEntry = (batch == null);
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
    // If we just added a brand-new MetricKey to this bucket and kept its additional tags, charge
    // it against the global stat-entry budget.
    if (isNewBucketEntry && newEntryUsedFullTags) {
      cardinalityLimiter.onNewStatEntryAdmitted();
    }
    // force keep keys if there are errors
    return span.getError() > 0;
  }

  /**
   * Builds a copy of {@code fullKey} with each present additional tag's value replaced by {@code
   * <tagKey>:blocked_by_tracer}. The set of configured tag keys present on the span is preserved as
   * dimensions; only the values are masked. Also fires the per-tag health metric for each masked
   * tag. Used when the bucket cardinality cap is hit and we need to suppress this span's
   * contribution to per-value aggregation without losing the dimension keys entirely.
   */
  private MetricKey rebuildKeyWithBlockedValues(CoreSpan<?> span, MetricKey fullKey) {
    List<UTF8BytesString> blockedTags = null;
    for (String tagKey : additionalTagKeys) {
      if (span.unsafeGetTag(tagKey) == null) continue;
      healthMetrics.onAdditionalTagValueCardinalityBlocked(tagKey);
      Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>> cacheAndCreator =
          ADDITIONAL_TAG_VALUES_CACHE.computeIfAbsent(tagKey, ADDITIONAL_TAG_VALUES_CACHE_ADDER);
      UTF8BytesString formatted =
          cacheAndCreator
              .getLeft()
              .computeIfAbsent(
                  AdditionalTagsCardinalityLimiter.BLOCKED_VALUE, cacheAndCreator.getRight());
      if (blockedTags == null) {
        blockedTags = new ArrayList<>(additionalTagKeys.size());
      }
      blockedTags.add(formatted);
    }
    return new MetricKey(
        fullKey.getResource(),
        fullKey.getService(),
        fullKey.getOperationName(),
        fullKey.getServiceSource(),
        fullKey.getType(),
        fullKey.getHttpStatusCode(),
        fullKey.isSynthetics(),
        fullKey.isTraceRoot(),
        fullKey.getSpanKind(),
        fullKey.getPeerTags(),
        fullKey.getHttpMethod(),
        fullKey.getHttpEndpoint(),
        fullKey.getGrpcStatusCode(),
        blockedTags == null ? Collections.emptyList() : blockedTags);
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

  static List<String> normalizeAdditionalTagKeys(Set<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> sorted = new ArrayList<>(configured);
    Collections.sort(sorted);
    if (sorted.size() > MAX_ADDITIONAL_TAG_KEYS) {
      log.warn(
          "Configured additional metric tag keys ({}) exceeds the supported limit of {}; "
              + "dropping extra keys: {}",
          sorted.size(),
          MAX_ADDITIONAL_TAG_KEYS,
          sorted.subList(MAX_ADDITIONAL_TAG_KEYS, sorted.size()));
      sorted = sorted.subList(0, MAX_ADDITIONAL_TAG_KEYS);
    }
    return Collections.unmodifiableList(new ArrayList<>(sorted));
  }

  private List<UTF8BytesString> getAdditionalTagsLengthCapped(CoreSpan<?> span) {
    if (additionalTagKeys.isEmpty()) {
      return Collections.emptyList();
    }
    List<UTF8BytesString> result = null;
    for (int i = 0; i < additionalTagKeys.size(); i++) {
      String tagKey = additionalTagKeys.get(i);
      Object value = span.unsafeGetTag(tagKey);
      if (value == null) {
        continue;
      }
      String rawValue = value.toString();
      String admittedValue = cardinalityLimiter.applyLengthCap(tagKey, rawValue);
      Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>> cacheAndCreator =
          ADDITIONAL_TAG_VALUES_CACHE.computeIfAbsent(tagKey, ADDITIONAL_TAG_VALUES_CACHE_ADDER);
      UTF8BytesString formatted =
          cacheAndCreator.getLeft().computeIfAbsent(admittedValue, cacheAndCreator.getRight());
      if (result == null) {
        result = new ArrayList<>(additionalTagKeys.size());
      }
      result.add(formatted);
    }
    return result == null ? Collections.emptyList() : result;
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
