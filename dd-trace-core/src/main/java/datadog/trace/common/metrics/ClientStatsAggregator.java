package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.api.DDSpanTypes.RPC;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ENDPOINT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateMetric.TOP_LEVEL_TAG;
import static datadog.trace.common.metrics.SignalItem.ClearSignal.CLEAR;
import static datadog.trace.common.metrics.SignalItem.ReportSignal.REPORT;
import static datadog.trace.common.metrics.SignalItem.StopSignal.STOP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.METRICS_AGGREGATOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.Queues;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.common.metrics.SignalItem.ReportSignal;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.core.SpanKindFilter;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientStatsAggregator implements MetricsAggregator, EventListener {

  private static final Logger log = LoggerFactory.getLogger(ClientStatsAggregator.class);

  private static final Map<String, String> DEFAULT_HEADERS =
      Collections.singletonMap(DDAgentApi.DATADOG_META_TRACER_VERSION, DDTraceCoreInfo.VERSION);

  private static final String SYNTHETICS_ORIGIN = "synthetics";

  private static final SpanKindFilter METRICS_ELIGIBLE_KINDS =
      SpanKindFilter.builder()
          .includeServer()
          .includeClient()
          .includeProducer()
          .includeConsumer()
          .build();

  private static final SpanKindFilter PEER_AGGREGATION_KINDS =
      SpanKindFilter.builder().includeClient().includeProducer().includeConsumer().build();

  private static final SpanKindFilter INTERNAL_KIND =
      SpanKindFilter.builder().includeInternal().build();

  private final Set<String> ignoredResources;
  private final Thread thread;
  private final MessagePassingQueue<InboxItem> inbox;
  private final Sink sink;
  private final Aggregator aggregator;
  private final long reportingInterval;
  private final TimeUnit reportingIntervalTimeUnit;
  private final DDAgentFeaturesDiscovery features;
  private final HealthMetrics healthMetrics;
  private final boolean includeEndpointInMetrics;

  /**
   * Cached peer-aggregation schema and the {@link DDAgentFeaturesDiscovery#peerTagsRevision()}
   * value it was built from. The producer-side hot path in {@link #publish(List)} checks the
   * current revision against {@code cachedPeerTagsRevision} and only rebuilds when they differ.
   *
   * <p>Both fields are {@code volatile} because {@code publish} is called on arbitrary producer
   * threads. The reset hook ({@link #resetCachedPeerAggSchema()}) runs on the aggregator thread and
   * only mutates the schema's internal handler state (not these fields).
   */
  private volatile long cachedPeerTagsRevision = -1L;

  private volatile PeerTagSchema cachedPeerAggSchema;

  private volatile AgentTaskScheduler.Scheduled<?> cancellation;

  public ClientStatsAggregator(
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
            V06_METRICS_ENDPOINT,
            config.isTracerMetricsBufferingEnabled(),
            false,
            DEFAULT_HEADERS),
        config.getTracerMetricsMaxAggregates(),
        config.getTracerMetricsMaxPending(),
        config.isTraceResourceRenamingEnabled());
  }

  ClientStatsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
      DDAgentFeaturesDiscovery features,
      HealthMetrics healthMetric,
      Sink sink,
      int maxAggregates,
      int queueSize,
      boolean includeEndpointInMetrics) {
    this(
        wellKnownTags,
        ignoredResources,
        features,
        healthMetric,
        sink,
        maxAggregates,
        queueSize,
        10,
        SECONDS,
        includeEndpointInMetrics);
  }

  ClientStatsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
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

  ClientStatsAggregator(
      Set<String> ignoredResources,
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
    this.includeEndpointInMetrics = includeEndpointInMetrics;
    this.inbox = Queues.mpscArrayQueue(queueSize);
    this.features = features;
    this.healthMetrics = healthMetric;
    this.sink = sink;
    this.aggregator =
        new Aggregator(
            metricWriter,
            inbox,
            maxAggregates,
            reportingInterval,
            timeUnit,
            healthMetric,
            this::resetCachedPeerAggSchema);
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
      // Sync the peer-aggregation schema once per trace. The cache is keyed on
      // features.peerTagsRevision(), which only bumps when the agent's peer-tag set actually
      // changes -- so the steady-state cost is a volatile read and a long compare.
      PeerTagSchema peerAggSchema = peerAggSchema(features.peerTagsRevision());
      for (CoreSpan<?> span : trace) {
        boolean isTopLevel = span.isTopLevel();
        if (shouldComputeMetric(span, isTopLevel)) {
          final CharSequence resourceName = span.getResourceName();
          if (resourceName != null && ignoredResources.contains(resourceName.toString())) {
            // skip publishing all children
            forceKeep = false;
            break;
          }
          counted++;
          forceKeep |= publish(span, isTopLevel, peerAggSchema);
        }
      }
      healthMetrics.onClientStatTraceComputed(counted, trace.size(), !forceKeep);
    }
    return forceKeep;
  }

  private boolean shouldComputeMetric(CoreSpan<?> span, boolean isTopLevel) {
    return (span.isMeasured() || isTopLevel || span.isKind(METRICS_ELIGIBLE_KINDS))
        && span.getLongRunningVersion()
            <= 0 // either not long-running or unpublished long-running span
        && span.getDurationNano() > 0;
  }

  private boolean publish(CoreSpan<?> span, boolean isTopLevel, PeerTagSchema peerAggSchema) {
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
    // DDSpan resolves this from a cached span.kind ordinal via a small lookup array, skipping a
    // tag-map lookup. Other CoreSpan impls fall back to the tag map by default.
    String spanKind = span.getSpanKindString();
    if (spanKind == null) {
      spanKind = "";
    }

    boolean error = span.getError() > 0;
    long tagAndDuration =
        span.getDurationNano() | (error ? ERROR_TAG : 0L) | (isTopLevel ? TOP_LEVEL_TAG : 0L);

    PeerTagSchema peerTagSchema = peerTagSchemaFor(span, peerAggSchema);
    String[] peerTagValues =
        peerTagSchema == null ? null : capturePeerTagValues(span, peerTagSchema);
    if (peerTagValues == null) {
      // capture returned no non-null values -- drop the schema reference so the consumer doesn't
      // bother iterating an all-null array.
      peerTagSchema = null;
    }

    SpanSnapshot snapshot =
        new SpanSnapshot(
            span.getResourceName(),
            span.getServiceName(),
            span.getOperationName(),
            span.getServiceNameSource(),
            spanType,
            span.getHttpStatusCode(),
            isSynthetic(span),
            span.getParentId() == 0,
            spanKind,
            peerTagSchema,
            peerTagValues,
            httpMethod,
            httpEndpoint,
            grpcStatusCode,
            tagAndDuration);
    if (!inbox.offer(snapshot)) {
      healthMetrics.onStatsInboxFull();
    }
    // force keep keys if there are errors
    return error;
  }

  /**
   * Returns the peer-aggregation schema synced to the given revision, rebuilding it if the cached
   * one is stale. Fast path: one volatile-read pair + a long compare. Rebuild is rare (peer-tag
   * config changes), so the synchronization is only on the slow path.
   */
  private PeerTagSchema peerAggSchema(long revision) {
    if (revision == cachedPeerTagsRevision) {
      return cachedPeerAggSchema;
    }
    return refreshPeerAggSchema(revision);
  }

  private synchronized PeerTagSchema refreshPeerAggSchema(long revision) {
    // Double-checked: another producer may have rebuilt while we were waiting on the monitor.
    if (revision == cachedPeerTagsRevision) {
      return cachedPeerAggSchema;
    }
    Set<String> names = features.peerTags();
    PeerTagSchema schema = (names == null || names.isEmpty()) ? null : PeerTagSchema.of(names);
    cachedPeerAggSchema = schema;
    cachedPeerTagsRevision = revision;
    return schema;
  }

  /**
   * Reset hook invoked on the aggregator thread at the end of each report cycle. Resets the cached
   * peer-aggregation schema's cardinality handlers so per-field budgets refresh in lockstep with
   * {@link AggregateEntry#resetCardinalityHandlers()}.
   */
  private void resetCachedPeerAggSchema() {
    PeerTagSchema schema = cachedPeerAggSchema;
    if (schema != null) {
      schema.resetCardinalityHandlers();
    }
  }

  /**
   * Picks the peer-tag schema for a span. The {@code peerAggSchema} argument is the per-trace
   * cached schema (synced from {@code features.peerTagsRevision()} once in {@link #publish(List)});
   * it's {@code null} when no peer tags are configured. For internal-kind spans the static {@link
   * PeerTagSchema#INTERNAL} schema is used regardless.
   */
  private static PeerTagSchema peerTagSchemaFor(CoreSpan<?> span, PeerTagSchema peerAggSchema) {
    if (peerAggSchema != null && span.isKind(PEER_AGGREGATION_KINDS)) {
      return peerAggSchema;
    }
    if (span.isKind(INTERNAL_KIND)) {
      return PeerTagSchema.INTERNAL;
    }
    return null;
  }

  /**
   * Captures the span's peer tag values into a {@code String[]} parallel to {@code schema.names}.
   * Returns {@code null} when none of the configured peer tags are set on the span.
   */
  private static String[] capturePeerTagValues(CoreSpan<?> span, PeerTagSchema schema) {
    String[] names = schema.names;
    int n = names.length;
    String[] values = null;
    for (int i = 0; i < n; i++) {
      Object v = span.unsafeGetTag(names[i]);
      if (v != null) {
        if (values == null) {
          values = new String[n];
        }
        values[i] = v.toString();
      }
    }
    return values;
  }

  private static boolean isSynthetic(CoreSpan<?> span) {
    CharSequence origin = span.getOrigin();
    return origin != null && SYNTHETICS_ORIGIN.contentEquals(origin);
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
      // Route the clear through the inbox so the aggregator thread is the only writer.
      // AggregateTable is not thread-safe; clearing it directly from this thread would race
      // with Drainer.accept on the aggregator thread.
      inbox.offer(CLEAR);
    }
  }

  private static final class ReportTask implements AgentTaskScheduler.Task<ClientStatsAggregator> {

    @Override
    public void run(ClientStatsAggregator target) {
      target.report();
    }
  }
}
