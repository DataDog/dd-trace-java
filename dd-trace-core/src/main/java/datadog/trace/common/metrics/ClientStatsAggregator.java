package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.api.DDSpanTypes.RPC;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ENDPOINT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
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
  private final AdditionalTagsSchema additionalTagsSchema;
  private final boolean includeEndpointInMetrics;

  /**
   * Cached peer-tag schema. Producers read this reference once per trace and pass it through to the
   * consumer in {@link SpanSnapshot}; they never inspect the schema's timestamp or rebuild it.
   * Reconciliation is the aggregator thread's job: {@link #resetCardinalityHandlers()} compares the
   * schema's {@link PeerTagSchema#lastTimeDiscovered} against {@link
   * DDAgentFeaturesDiscovery#getLastTimeDiscovered()} once per reporting cycle and either updates
   * the timestamp in place (when the tag set is unchanged, preserving the schema's warm cardinality
   * handlers) or swaps in a freshly-built schema.
   *
   * <p>An empty schema (size 0) represents the "peer tags unconfigured" state; {@code null} only on
   * the bootstrap window before {@link #bootstrapPeerTagSchema()} runs on the first publish.
   *
   * <p>{@code volatile} so the consumer's reconcile-time replacement is visible to producer
   * threads; the schema's own internal mutable state (handlers, block counters, timestamp) is
   * exercised only on the aggregator thread.
   */
  private volatile PeerTagSchema cachedPeerTagSchema;

  private volatile AgentTaskScheduler.Scheduled<?> cancellation;

  public ClientStatsAggregator(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      HealthMetrics healthMetrics) {
    this(
        config.getWellKnownTags(),
        config.getMetricsIgnoredResources(),
        AdditionalTagsSchema.from(
            config.getTraceStatsAdditionalTags(),
            config.getTraceStatsAdditionalTagsCardinalityLimit(),
            healthMetrics),
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
        AdditionalTagsSchema.EMPTY,
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
      AdditionalTagsSchema additionalTagsSchema,
      DDAgentFeaturesDiscovery features,
      HealthMetrics healthMetric,
      Sink sink,
      int maxAggregates,
      int queueSize,
      boolean includeEndpointInMetrics) {
    this(
        wellKnownTags,
        ignoredResources,
        additionalTagsSchema,
        features,
        healthMetric,
        sink,
        maxAggregates,
        queueSize,
        10,
        SECONDS,
        includeEndpointInMetrics);
  }

  /** Test-only: defaults to no additional tags schema. */
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
        wellKnownTags,
        ignoredResources,
        AdditionalTagsSchema.EMPTY,
        features,
        healthMetric,
        sink,
        maxAggregates,
        queueSize,
        reportingInterval,
        timeUnit,
        includeEndpointInMetrics);
  }

  ClientStatsAggregator(
      WellKnownTags wellKnownTags,
      Set<String> ignoredResources,
      AdditionalTagsSchema additionalTagsSchema,
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
        additionalTagsSchema,
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

  /** Test-only: defaults to no additional tags schema. */
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
    this(
        ignoredResources,
        AdditionalTagsSchema.EMPTY,
        features,
        healthMetric,
        sink,
        metricWriter,
        maxAggregates,
        queueSize,
        reportingInterval,
        timeUnit,
        includeEndpointInMetrics);
  }

  ClientStatsAggregator(
      Set<String> ignoredResources,
      AdditionalTagsSchema additionalTagsSchema,
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
    this.additionalTagsSchema = additionalTagsSchema;
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
            this::resetCardinalityHandlers);
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
      // Producer-side fast path: one volatile read and use whatever schema is currently cached.
      // The aggregator thread keeps this schema in sync with feature discovery in
      // resetCardinalityHandlers(). The only producer-side rebuild is the one-time bootstrap on
      // the first publish.
      PeerTagSchema peerTagSchema = cachedPeerTagSchema;
      if (peerTagSchema == null) {
        peerTagSchema = bootstrapPeerTagSchema();
      }
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
          forceKeep |= publish(span, isTopLevel, peerTagSchema);
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

  private boolean publish(CoreSpan<?> span, boolean isTopLevel, PeerTagSchema peerTagSchema) {
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

    PeerTagSchema spanPeerTagSchema = peerTagSchemaFor(span, peerTagSchema);
    String[] peerTagValues =
        spanPeerTagSchema == null ? null : capturePeerTagValues(span, spanPeerTagSchema);
    if (peerTagValues == null) {
      // capture returned no non-null values -- drop the schema reference so the consumer doesn't
      // bother iterating an all-null array.
      spanPeerTagSchema = null;
    }

    String[] additionalTagValues = captureAdditionalTagValues(span);
    // Capture the schema reference too so producer and consumer agree on indexing -- mirrors the
    // peer-tag schema handoff. Drop the schema reference when no values fired so the consumer can
    // short-circuit on schema == null.
    AdditionalTagsSchema snapshotAdditionalTagsSchema =
        additionalTagValues == null ? null : additionalTagsSchema;

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
            spanPeerTagSchema,
            peerTagValues,
            httpMethod,
            httpEndpoint,
            grpcStatusCode,
            snapshotAdditionalTagsSchema,
            additionalTagValues,
            tagAndDuration);
    if (!inbox.offer(snapshot)) {
      healthMetrics.onStatsInboxFull();
    }
    // force keep keys if there are errors
    return error;
  }

  /**
   * Captures the span's additional-metric-tag values into a {@code String[]} parallel to the
   * schema's name order. Returns {@code null} when no additional tags are configured or none of
   * the configured keys are set on the span. Raw values only -- length cap and canonicalization
   * run on the aggregator thread.
   */
  private String[] captureAdditionalTagValues(CoreSpan<?> span) {
    int n = additionalTagsSchema.size();
    if (n == 0) {
      return null;
    }
    String[] values = null;
    for (int i = 0; i < n; i++) {
      Object v = span.unsafeGetTag(additionalTagsSchema.name(i));
      if (v != null) {
        if (values == null) {
          values = new String[n];
        }
        values[i] = v.toString();
      }
    }
    return values;
  }

  /**
   * One-time producer-side bootstrap of {@link #cachedPeerTagSchema}. Synchronized double-check
   * guards against two producers racing on the very first publish; after this returns, {@code
   * cachedPeerTagSchema} is non-null forever and the aggregator thread is the sole subsequent
   * mutator (see {@link #reconcilePeerTagSchema()}).
   */
  private synchronized PeerTagSchema bootstrapPeerTagSchema() {
    PeerTagSchema cached = cachedPeerTagSchema;
    if (cached != null) {
      return cached;
    }
    PeerTagSchema schema = buildPeerTagSchema();
    cachedPeerTagSchema = schema;
    return schema;
  }

  /** Builds a fresh {@link PeerTagSchema} from the current state of feature discovery. */
  private PeerTagSchema buildPeerTagSchema() {
    Set<String> names = features.peerTags();
    return PeerTagSchema.of(
        names == null ? Collections.emptySet() : names,
        features.getLastTimeDiscovered(),
        healthMetrics);
  }

  /**
   * Single reset hook invoked on the aggregator thread at the end of each report cycle. Reconciles
   * the cached peer-tag schema against the latest feature discovery, then resets all cardinality
   * state in lockstep: the static property handlers + {@code PeerTagSchema.INTERNAL} (via {@link
   * AggregateEntry#resetCardinalityHandlers()}), the cached peer-tag schema (with whatever
   * reconciliation just produced), and the additional-tags schema. New handlers added anywhere in
   * this pipeline should be reset from here.
   */
  private void resetCardinalityHandlers() {
    reconcilePeerTagSchema();
    AggregateEntry.resetCardinalityHandlers();
    PeerTagSchema schema = cachedPeerTagSchema;
    if (schema != null) {
      schema.resetCardinalityHandlers();
    }
    additionalTagsSchema.resetCardinalityHandlers();
  }

  /**
   * Reconciles {@link #cachedPeerTagSchema} with the latest feature discovery. Runs on the
   * aggregator thread once per reporting cycle. Cheap fast path: a long compare against the cached
   * schema's embedded timestamp short-circuits when discovery hasn't refreshed since the schema was
   * built. On mismatch, a set compare distinguishes "discovery refreshed but tags unchanged" (just
   * bump the timestamp in place to preserve the warm cardinality handlers) from "tags actually
   * changed" (build a new schema and swap the volatile reference).
   */
  private void reconcilePeerTagSchema() {
    PeerTagSchema cached = cachedPeerTagSchema;
    if (cached == null) {
      // First reset before the first publish -- producer-side bootstrap hasn't run yet.
      return;
    }
    long latestDiscoveredAt = features.getLastTimeDiscovered();
    if (cached.lastTimeDiscovered == latestDiscoveredAt) {
      return;
    }
    Set<String> latestNames = features.peerTags();
    Set<String> normalized = latestNames == null ? Collections.emptySet() : latestNames;
    if (cached.hasSameTagsAs(normalized)) {
      cached.lastTimeDiscovered = latestDiscoveredAt;
    } else {
      cachedPeerTagSchema = PeerTagSchema.of(normalized, latestDiscoveredAt, healthMetrics);
    }
  }

  /**
   * Picks the peer-tag schema for a span. The {@code peerTagSchema} argument is the per-trace
   * cached schema (read once in {@link #publish(List)} via the volatile {@link
   * #cachedPeerTagSchema}, with {@link #bootstrapPeerTagSchema()} taking care of the first-publish
   * window) -- always non-null but possibly empty when peer tags are unconfigured. For
   * internal-kind spans the static {@link PeerTagSchema#INTERNAL} schema is used regardless.
   */
  private static PeerTagSchema peerTagSchemaFor(CoreSpan<?> span, PeerTagSchema peerTagSchema) {
    if (peerTagSchema.size() > 0 && span.isKind(PEER_AGGREGATION_KINDS)) {
      return peerTagSchema;
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
