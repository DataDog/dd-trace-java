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
import datadog.trace.util.concurrent.MpscRingBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  /** Capacity of the small MPSC queue carrying control signals (REPORT / STOP / CLEAR). */
  private static final int SIGNAL_INBOX_CAPACITY = 16;

  private final Set<String> ignoredResources;
  private final Thread thread;

  /**
   * Producer/consumer data channel: a {@link MpscRingBuffer} of pre-allocated, recyclable {@link
   * SpanSnapshot} slots. Producers mutate slots in place via {@link #slotFiller} -- no per-publish
   * allocation.
   */
  private final MpscRingBuffer<SpanSnapshot> dataInbox;

  /**
   * Separate small queue for control signals. Kept off the data ring because the ring only holds
   * one element type (the slot), and routing signals through a side channel lets the aggregator
   * service them ahead of the data backlog each loop iteration.
   */
  private final MessagePassingQueue<SignalItem> signalInbox;

  /**
   * Slot filler captured as an instance field so the lambda is allocated once at construction. The
   * method reference closes over {@code this}, but the field is per-aggregator so context lives in
   * the {@code (CoreSpan, isTopLevel)} arguments passed by the producer at each call -- no
   * per-publish capture.
   */
  private final datadog.trace.api.function.TriConsumer<CoreSpan<?>, Boolean, SpanSnapshot>
      slotFiller = this::fillSlot;

  private final Sink sink;
  private final Aggregator aggregator;
  private final long reportingInterval;
  private final TimeUnit reportingIntervalTimeUnit;
  private final DDAgentFeaturesDiscovery features;
  private final HealthMetrics healthMetrics;
  private final boolean includeEndpointInMetrics;

  /**
   * Cached peer-aggregation schema. Producers read this reference once per trace and pass it
   * through to the consumer in {@link SpanSnapshot}; they never inspect the schema's discovery
   * state or rebuild it. Reconciliation is the aggregator thread's job: {@link
   * #reconcilePeerTagSchema()} compares the schema's {@link PeerTagSchema#state} against {@link
   * DDAgentFeaturesDiscovery#state()} once per reporting cycle and either updates the state in
   * place (when the tag set is unchanged) or swaps in a freshly-built schema.
   *
   * <p>{@code null} only on the bootstrap window before {@link #bootstrapPeerTagSchema()} runs on
   * the first publish.
   *
   * <p>{@code volatile} so the consumer's reconcile-time replacement is visible to producer
   * threads; the schema's own internal mutable state ({@link PeerTagSchema#state}) is exercised
   * only on the aggregator thread.
   */
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
    this.dataInbox = new MpscRingBuffer<>(SpanSnapshot::new, queueSize);
    this.signalInbox = Queues.mpscArrayQueue(SIGNAL_INBOX_CAPACITY);
    this.features = features;
    this.healthMetrics = healthMetric;
    this.sink = sink;
    this.aggregator =
        new Aggregator(
            metricWriter,
            dataInbox,
            signalInbox,
            maxAggregates,
            reportingInterval,
            timeUnit,
            healthMetric,
            this::reconcilePeerTagSchema);
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
      published = signalInbox.offer(REPORT);
      ++attempts;
    } while (!published && attempts < 10);
    if (!published) {
      log.debug("Skipped metrics reporting because the signal queue is full");
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
      published = signalInbox.offer(reportSignal);
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

    // Fast-path the inbox-full case before tag extraction. size() is approximate but that's fine:
    // a false "full" reading just causes a drop, and a real one is correctly identified.
    if (dataInbox.size() >= dataInbox.capacity()) {
      healthMetrics.onStatsInboxFull();
      return error;
    }

    // tryWrite claims a slot, runs slotFiller to populate it in place, and publishes. No
    // SpanSnapshot is allocated -- the slot is one of the ring's pre-allocated instances.
    if (!dataInbox.tryWrite(span, isTopLevel, slotFiller)) {
      healthMetrics.onStatsInboxFull();
    }
    return error;
  }

  /**
   * Producer-side slot fill. Runs inside {@link MpscRingBuffer#tryWrite} after the producer has
   * claimed a sequence but before the slot is visible to the aggregator. Reads from {@code span}
   * and instance state, writes every field on {@code slot} (including {@code null} where
   * applicable) so stale values from a prior occupant of this slot don't bleed through.
   */
  private void fillSlot(CoreSpan<?> span, Boolean isTopLevelBoxed, SpanSnapshot slot) {
    final boolean isTopLevel = isTopLevelBoxed;
    final boolean error = span.getError() > 0;

    // Extract HTTP method and endpoint only if the feature is enabled.
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

    slot.resourceName = span.getResourceName();
    slot.serviceName = span.getServiceName();
    slot.operationName = span.getOperationName();
    slot.serviceNameSource = span.getServiceNameSource();
    slot.spanType = spanType;
    slot.httpStatusCode = span.getHttpStatusCode();
    slot.synthetic = isSynthetic(span);
    slot.traceRoot = span.getParentId() == 0;
    slot.spanKind = spanKind;
    slot.peerTagSchema = peerTagSchema;
    slot.peerTagValues = peerTagValues;
    slot.httpMethod = httpMethod;
    slot.httpEndpoint = httpEndpoint;
    slot.grpcStatusCode = grpcStatusCode;
    slot.tagAndDuration = tagAndDuration;
  }

  /**
   * Picks the peer-tag schema for a span. For internal-kind spans we always use the static {@link
   * PeerTagSchema#INTERNAL} singleton (one entry for {@code base.service}); for {@code
   * client}/{@code producer}/{@code consumer} kinds we use the cached peer-aggregation schema
   * synced from {@link DDAgentFeaturesDiscovery#peerTags()}. Other kinds get {@code null}.
   */
  private PeerTagSchema peerTagSchemaFor(CoreSpan<?> span) {
    if (span.isKind(PEER_AGGREGATION_KINDS)) {
      PeerTagSchema schema = cachedPeerTagSchema;
      if (schema == null) {
        schema = bootstrapPeerTagSchema();
      }
      return schema.size() > 0 ? schema : null;
    }
    if (span.isKind(INTERNAL_KIND)) {
      return PeerTagSchema.INTERNAL;
    }
    return null;
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

  /**
   * Builds a fresh {@link PeerTagSchema} from the current state of feature discovery.
   *
   * <p>Read order matters: {@code DDAgentFeaturesDiscovery} exposes {@code peerTags()} and {@code
   * state()} as two separate accessors, each reading its volatile {@code discoveryState}
   * independently. If a discovery refresh interleaves between the two reads, we want to be left
   * with a schema whose embedded state is *stale* relative to its tag set rather than the other way
   * around -- that way the next reconcile sees a state mismatch and re-runs the deep compare to
   * pick up the change, instead of short-circuiting on a too-fresh state and missing it.
   *
   * <p>So read {@code state()} first, then {@code peerTags()}.
   */
  private PeerTagSchema buildPeerTagSchema() {
    String state = features.state();
    Set<String> names = features.peerTags();
    return PeerTagSchema.of(names == null ? Collections.<String>emptySet() : names, state);
  }

  /**
   * Reconciles {@link #cachedPeerTagSchema} with the latest feature discovery. Runs on the
   * aggregator thread once per reporting cycle via the reset hook passed to {@link Aggregator}.
   * Cheap fast path: an equality check against the cached schema's embedded {@link
   * DDAgentFeaturesDiscovery#state()} hash short-circuits when discovery's response hasn't changed
   * since the schema was built. On mismatch, a set compare distinguishes "discovery response
   * changed but peer tags are the same" (just update the cached state in place) from "tags actually
   * changed" (build a new schema and swap the volatile reference).
   */
  private void reconcilePeerTagSchema() {
    PeerTagSchema cached = cachedPeerTagSchema;
    if (cached == null) {
      // First reset before the first publish -- producer-side bootstrap hasn't run yet.
      return;
    }
    String latestState = features.state();
    if (Objects.equals(cached.state, latestState)) {
      return;
    }
    Set<String> latestNames = features.peerTags();
    Set<String> normalized = latestNames == null ? Collections.<String>emptySet() : latestNames;
    if (cached.hasSameTagsAs(normalized)) {
      cached.state = latestState;
    } else {
      cachedPeerTagSchema = PeerTagSchema.of(normalized, latestState);
    }
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
    signalInbox.offer(STOP);
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
      // Route the clear through the signal channel so the aggregator thread is the only writer.
      // AggregateTable is not thread-safe; mutating it directly from this thread would race
      // with snapshot processing on the aggregator thread.
      //
      // Best-effort single offer rather than the retry-loop pattern in report(). If the signal
      // queue is full at downgrade time the clear is dropped, but the system self-heals:
      // features.discover() already flipped supportsMetrics() false, so producer publish() calls
      // now skip the data ring; the aggregator drains existing snapshots and ships them on the
      // next report cycle; the sink rejects that payload and fires DOWNGRADED again, which
      // retries disable() against a now-empty signal queue. Worst case: one extra reporting
      // cycle of stale data.
      signalInbox.offer(CLEAR);
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
