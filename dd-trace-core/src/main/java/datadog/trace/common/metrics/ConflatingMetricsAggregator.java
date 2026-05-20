package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.api.DDSpanTypes.RPC;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ENDPOINT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.common.metrics.AggregateEntry.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateEntry.TOP_LEVEL_TAG;
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

public final class ConflatingMetricsAggregator implements MetricsAggregator, EventListener {

  private static final Logger log = LoggerFactory.getLogger(ConflatingMetricsAggregator.class);

  private static final Map<String, String> DEFAULT_HEADERS =
      Collections.singletonMap(DDAgentApi.DATADOG_META_TRACER_VERSION, DDTraceCoreInfo.VERSION);

  private static final CharSequence SYNTHETICS_ORIGIN = "synthetics";

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
   * Cached peer-aggregation schema, keyed by reference equality of the {@code Set<String>} returned
   * by {@link DDAgentFeaturesDiscovery#peerTags()}. {@code DDAgentFeaturesDiscovery} caches the Set
   * on its current state, so reference identity changes exactly when discovery replaces state with
   * a new tag configuration -- a single volatile read + a reference compare on the steady-state hot
   * path. The {@code synchronized} refresh is the only allocator on a miss.
   *
   * <p>Both fields are written together inside the synchronized block, but read independently --
   * the reference-equality check on the source Set is what guards against using a stale schema, so
   * tearing on the schema field alone is not a correctness concern.
   */
  private volatile Set<String> cachedPeerTagsSource;

  private volatile PeerTagSchema cachedPeerTagSchema;

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

  ConflatingMetricsAggregator(
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

  ConflatingMetricsAggregator(
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
            metricWriter, inbox, maxAggregates, reportingInterval, timeUnit, healthMetric);
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
        if (shouldComputeMetric(span, isTopLevel)) {
          final CharSequence resourceName = span.getResourceName();
          if (resourceName != null && ignoredResources.contains(resourceName.toString())) {
            // skip publishing all children
            forceKeep = false;
            break;
          }
          counted++;
          forceKeep |= publish(span, isTopLevel);
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

  private boolean publish(CoreSpan<?> span, boolean isTopLevel) {
    // Error decision drives force-keep sampling regardless of whether the snapshot gets queued.
    boolean error = span.getError() > 0;

    // Fast-path the inbox-full case before any tag extraction or snapshot allocation. size() is
    // approximate on jctools' MPSC queue but that's fine: if we under-estimate, we fall through
    // and let inbox.offer be the source of truth (existing behavior); if we over-estimate, we
    // drop a snapshot that would have fit -- acceptable, onStatsInboxFull was going to fire
    // imminently anyway.
    if (inbox.size() >= inbox.capacity()) {
      healthMetrics.onStatsInboxFull();
      return error;
    }

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
    // CharSequence default keeps unsafeGetTag's generic at CharSequence so UTF8BytesString
    // tag values don't trigger a ClassCastException on the String assignment.
    final String spanKind = span.unsafeGetTag(SPAN_KIND, (CharSequence) "").toString();

    long tagAndDuration =
        span.getDurationNano() | (error ? ERROR_TAG : 0L) | (isTopLevel ? TOP_LEVEL_TAG : 0L);

    PeerTagSchema peerTagSchema = peerTagSchemaFor(span);
    String[] peerTagValues =
        peerTagSchema == null ? null : capturePeerTagValues(span, peerTagSchema);
    if (peerTagValues == null) {
      // No tags fired -- drop the schema reference so the consumer doesn't bother iterating an
      // all-null array.
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
   * Picks the peer-tag schema for a span. For internal-kind spans we always use the static {@link
   * PeerTagSchema#INTERNAL} singleton (one entry for {@code base.service}); for {@code
   * client}/{@code producer}/{@code consumer} kinds we use the cached peer-aggregation schema
   * synced from {@link DDAgentFeaturesDiscovery#peerTags()}. Other kinds get {@code null}.
   */
  private PeerTagSchema peerTagSchemaFor(CoreSpan<?> span) {
    if (span.isKind(PEER_AGGREGATION_KINDS)) {
      PeerTagSchema schema = currentPeerAggSchema();
      return schema.size() > 0 ? schema : null;
    }
    if (span.isKind(INTERNAL_KIND)) {
      return PeerTagSchema.INTERNAL;
    }
    return null;
  }

  /**
   * Returns the currently-cached peer-aggregation schema, rebuilding it if {@link
   * DDAgentFeaturesDiscovery#peerTags()} has returned a different {@code Set} reference since the
   * last cache. Steady-state cost: one volatile read + one reference compare.
   */
  private PeerTagSchema currentPeerAggSchema() {
    Set<String> current = features.peerTags();
    if (current == cachedPeerTagsSource) {
      return cachedPeerTagSchema;
    }
    return refreshPeerAggSchema(current);
  }

  private synchronized PeerTagSchema refreshPeerAggSchema(Set<String> current) {
    // Double-checked: another producer may have rebuilt while we were waiting on the monitor.
    if (current == cachedPeerTagsSource) {
      return cachedPeerTagSchema;
    }
    PeerTagSchema schema = PeerTagSchema.of(current);
    cachedPeerTagSchema = schema;
    cachedPeerTagsSource = current;
    return schema;
  }

  /**
   * Captures the span's peer-tag values into a {@code String[]} parallel to {@code schema.names}.
   * Slots remain {@code null} for tags the span didn't set; the array itself is lazily allocated on
   * the first hit so spans that fire no peer tags pay zero allocation. Returns {@code null} when
   * none of the configured peer tags are set on the span.
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
    return span.getOrigin() != null && SYNTHETICS_ORIGIN.equals(span.getOrigin().toString());
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
      // AggregateTable is not thread-safe; calling clearAggregates() directly from this thread
      // would race with Drainer.accept on the aggregator thread.
      //
      // Best-effort single offer rather than the retry-loop pattern in report(). If the inbox is
      // full at downgrade time the clear is dropped, but the system self-heals: features.discover()
      // already flipped supportsMetrics() false, so producer publish() calls now skip the inbox;
      // the aggregator drains existing snapshots and ships them on the next report cycle; the
      // sink rejects that payload and fires DOWNGRADED again, which retries disable() against a
      // now-empty inbox. Worst case: one extra reporting cycle of stale data.
      inbox.offer(CLEAR);
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
