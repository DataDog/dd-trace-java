package datadog.trace.core.monitor;

import static datadog.trace.api.DDSpanId.ZERO;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.api.statsd.StatsDCountReporter;
import datadog.metrics.api.statsd.StatsDCounterKey;
import datadog.trace.api.cache.RadixTreeCache;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import datadog.trace.core.propagation.opg.OrgGuard;
import datadog.trace.util.Accumulator;
import datadog.trace.util.AgentTaskScheduler;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

public class TracerHealthMetrics extends HealthMetrics implements AutoCloseable {

  private static final IntFunction<String[]> STATUS_TAGS =
      httpStatus -> new String[] {"status:" + httpStatus};

  private static final String[] NO_TAGS = new String[0];
  private static final String[] COLLAPSED_WHOLE_KEY_TAGS = new String[] {"collapsed:whole_key"};
  private final RadixTreeCache<String[]> statusTagsCache =
      new RadixTreeCache<>(16, 32, STATUS_TAGS, 200, 400);

  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile AgentTaskScheduler.Scheduled<TracerHealthMetrics> cancellation;

  private final Accumulator<Metric> metricAccumulator = Accumulator.of(Metric.class);
  private volatile Accumulator.Counts<Metric> storedTotal = Accumulator.Counts.zero(Metric.class);

  private final StatsDClient statsd;
  private final long interval;
  private final TimeUnit units;

  @Override
  public void start() {
    if (started.compareAndSet(false, true)) {
      cancellation =
          AgentTaskScheduler.get()
              .scheduleAtFixedRate(new Flush(), this, interval, interval, units);
    }
  }

  public TracerHealthMetrics(final StatsDClient statsd) {
    this(statsd, 30, SECONDS);
  }

  public TracerHealthMetrics(final StatsDClient statsd, long interval, TimeUnit units) {
    this.statsd = statsd;
    this.interval = interval;
    this.units = units;
  }

  @Override
  public void onStart(final int queueCapacity) {
    statsd.gauge("queue.max_length", queueCapacity, NO_TAGS);
  }

  @Override
  public void onShutdown(final boolean flushSuccess) {}

  @Override
  public void onPublish(final List<DDSpan> trace, final int samplingPriority) {
    switch (samplingPriority) {
      case USER_DROP:
        metricAccumulator.inc(Metric.USER_DROP_ENQUEUED_TRACES);
        break;
      case USER_KEEP:
        metricAccumulator.inc(Metric.USER_KEEP_ENQUEUED_TRACES);
        break;
      case SAMPLER_DROP:
        metricAccumulator.inc(Metric.SAMPLER_DROP_ENQUEUED_TRACES);
        break;
      case SAMPLER_KEEP:
        metricAccumulator.inc(Metric.SAMPLER_KEEP_ENQUEUED_TRACES);
        break;
      default:
        metricAccumulator.inc(Metric.UNSET_PRIORITY_ENQUEUED_TRACES);
    }
    metricAccumulator.add(Metric.ENQUEUED_SPANS, trace.size());
    checkForClientSpansWithoutContext(trace);
  }

  private void checkForClientSpansWithoutContext(final List<DDSpan> trace) {
    for (DDSpan span : trace) {
      if (span != null && span.getParentId() == ZERO) {
        String spanKind = span.getTag(SPAN_KIND, "undefined");
        if (SPAN_KIND_CLIENT.equals(spanKind)) {
          metricAccumulator.inc(Metric.CLIENT_SPANS_WITHOUT_CONTEXT);
        }
      }
    }
  }

  @Override
  public void onFailedPublish(final int samplingPriority, final int spanCount) {
    switch (samplingPriority) {
      case USER_DROP:
        metricAccumulator.add(Metric.USER_DROP_DROPPED_SPANS, spanCount);
        metricAccumulator.inc(Metric.USER_DROP_DROPPED_TRACES);
        break;
      case USER_KEEP:
        metricAccumulator.add(Metric.USER_KEEP_DROPPED_SPANS, spanCount);
        metricAccumulator.inc(Metric.USER_KEEP_DROPPED_TRACES);
        break;
      case SAMPLER_DROP:
        metricAccumulator.add(Metric.SAMPLER_DROP_DROPPED_SPANS, spanCount);
        metricAccumulator.inc(Metric.SAMPLER_DROP_DROPPED_TRACES);
        break;
      case SAMPLER_KEEP:
        metricAccumulator.add(Metric.SAMPLER_KEEP_DROPPED_SPANS, spanCount);
        metricAccumulator.inc(Metric.SAMPLER_KEEP_DROPPED_TRACES);
        break;
      default:
        metricAccumulator.add(Metric.UNSET_PRIORITY_DROPPED_SPANS, spanCount);
        metricAccumulator.inc(Metric.UNSET_PRIORITY_DROPPED_TRACES);
    }
  }

  @Override
  public void onPartialPublish(final int numberOfDroppedSpans) {
    metricAccumulator.inc(Metric.PARTIAL_TRACES);
    metricAccumulator.add(Metric.SAMPLER_DROP_DROPPED_SPANS, numberOfDroppedSpans);
  }

  @Override
  public void onScheduleFlush(final boolean previousIncomplete) {
    // not recorded
  }

  @Override
  public void onFlush(final boolean early) {}

  @Override
  public void onPartialFlush(final int sizeInBytes) {
    metricAccumulator.add(Metric.PARTIAL_BYTES, sizeInBytes);
  }

  @Override
  public void onSingleSpanSample() {
    metricAccumulator.inc(Metric.SINGLE_SPAN_SAMPLED);
  }

  @Override
  public void onSingleSpanUnsampled() {
    metricAccumulator.inc(Metric.SINGLE_SPAN_UNSAMPLED);
  }

  @Override
  public void onSerialize(final int serializedSizeInBytes) {
    // DQH - Because of Java tracer's 2 phase acceptance and serialization scheme, this doesn't
    // map precisely
    metricAccumulator.add(Metric.ENQUEUED_BYTES, serializedSizeInBytes);
  }

  @Override
  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {
    if (trace != null) {
      metricAccumulator.inc(Metric.SERIAL_FAILED_DROPPED_TRACES);
      metricAccumulator.add(Metric.SERIAL_FAILED_DROPPED_SPANS, trace.size());
    }
  }

  @Override
  public void onCreateSpan() {
    metricAccumulator.inc(Metric.CREATED_SPANS);
  }

  @Override
  public void onFinishSpan() {
    metricAccumulator.inc(Metric.FINISHED_SPANS);
  }

  @Override
  public void onCreateTrace() {
    metricAccumulator.inc(Metric.CREATED_TRACES);
  }

  @Override
  public void onScopeCloseError(boolean manual) {
    if (manual) {
      metricAccumulator.inc(Metric.SCOPE_CLOSE_ERRORS);
      metricAccumulator.inc(Metric.USER_SCOPE_CLOSE_ERRORS);
    } else {
      metricAccumulator.inc(Metric.SCOPE_CLOSE_ERRORS);
    }
  }

  @Override
  public void onCaptureContinuation() {
    metricAccumulator.inc(Metric.CAPTURED_CONTINUATIONS);
  }

  @Override
  public void onCancelContinuation() {
    metricAccumulator.inc(Metric.CANCELLED_CONTINUATIONS);
  }

  @Override
  public void onFinishContinuation() {
    metricAccumulator.inc(Metric.FINISHED_CONTINUATIONS);
  }

  @Override
  public void onActivateScope() {
    metricAccumulator.inc(Metric.ACTIVATED_SCOPES);
  }

  @Override
  public void onCloseScope() {
    metricAccumulator.inc(Metric.CLOSED_SCOPES);
  }

  @Override
  public void onScopeStackOverflow() {
    metricAccumulator.inc(Metric.SCOPE_STACK_OVERFLOW);
  }

  @Override
  public void onOrgGuardEnforce(OrgGuard.Reason reason) {
    switch (reason) {
      case MISMATCH:
        metricAccumulator.inc(Metric.ORG_GUARD_ENFORCE_MISMATCH);
        break;
      case STRICT_MISSING:
        metricAccumulator.inc(Metric.ORG_GUARD_ENFORCE_STRICT_MISSING);
        break;
    }
  }

  @Override
  public void onSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    onSendAttempt(traceCount, sizeInBytes, response);
  }

  @Override
  public void onFailedSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    onSendAttempt(traceCount, sizeInBytes, response);
  }

  @Override
  public void onLongRunningUpdate(final int dropped, final int write, final int expired) {
    metricAccumulator.add(Metric.LONG_RUNNING_TRACES_WRITE, write);
    metricAccumulator.add(Metric.LONG_RUNNING_TRACES_DROPPED, dropped);
    metricAccumulator.add(Metric.LONG_RUNNING_TRACES_EXPIRED, expired);
  }

  private void onSendAttempt(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    metricAccumulator.inc(Metric.API_REQUESTS);
    metricAccumulator.add(Metric.FLUSHED_TRACES, traceCount);
    // TODO: missing queue.spans (# of spans being sent)
    metricAccumulator.add(Metric.FLUSHED_BYTES, sizeInBytes);

    if (response.exception().isPresent()) {
      // covers communication errors -- both not receiving a response or
      // receiving malformed response (even when otherwise successful)
      metricAccumulator.inc(Metric.API_ERRORS);
    }

    if (200 == response.status().orElse(0)) {
      metricAccumulator.inc(Metric.API_RESPONSES_OK);
    }

    final int status = response.status().orElse(0);
    if (status != 0 && 200 != status) {
      statsd.incrementCounter("api.responses.total", statusTagsCache.get(status));
    }
  }

  @Override
  public void onClientStatTraceComputed(int countedSpans, int totalSpans, boolean dropped) {
    metricAccumulator.inc(Metric.CLIENT_STATS_PROCESSED_TRACES);
    metricAccumulator.add(Metric.CLIENT_STATS_PROCESSED_SPANS, countedSpans);
    if (dropped) {
      metricAccumulator.inc(Metric.CLIENT_STATS_P0_DROPPED_TRACES);
      metricAccumulator.add(Metric.CLIENT_STATS_P0_DROPPED_SPANS, totalSpans);
    }
  }

  @Override
  public void onClientStatPayloadSent() {
    metricAccumulator.inc(Metric.CLIENT_STATS_REQUESTS);
  }

  @Override
  public void onClientStatDowngraded() {
    metricAccumulator.inc(Metric.CLIENT_STATS_DOWNGRADES);
  }

  @Override
  public void onClientStatErrorReceived() {
    metricAccumulator.inc(Metric.CLIENT_STATS_ERRORS);
  }

  @Override
  public void onStatsAggregateDropped() {
    metricAccumulator.inc(Metric.STATS_AGGREGATE_DROPPED);
    statsd.count("datadog.tracer.stats.collapsed_spans", 1, COLLAPSED_WHOLE_KEY_TAGS);
  }

  @Override
  public void onStatsInboxFull() {
    metricAccumulator.inc(Metric.STATS_INBOX_FULL);
  }

  @Override
  public void onTagCardinalityBlocked(String[] statsDTag, long count) {
    statsd.count("datadog.tracer.stats.collapsed_spans", count, statsDTag);
  }

  @Override
  public void close() {
    if (null != cancellation) {
      cancellation.cancel();
    }
  }

  private static class Flush implements AgentTaskScheduler.Task<TracerHealthMetrics> {

    @Override
    public void run(TracerHealthMetrics target) {
      Accumulator.Counts<Metric> delta = target.metricAccumulator.accumulateAndReset();
      StatsDCountReporter.report(target.statsd, delta);
      target.storedTotal = target.storedTotal.plus(delta);
    }
  }

  @Override
  public String summary() {
    Accumulator.Counts<Metric> live = storedTotal.plus(metricAccumulator.sum());
    StringBuilder summary = new StringBuilder();
    for (Metric metric : live.keys()) {
      if (summary.length() > 0) {
        summary.append('\n');
      }
      summary.append(metric.getSummaryLabel()).append('=').append(live.get(metric));
    }
    return summary.toString();
  }

  /**
   * One counter tracked by {@link TracerHealthMetrics}: a dogstatsd metric name + tags, plus the
   * label {@link TracerHealthMetrics#summary()} renders it under. One constant per (counter, tag)
   * combination -- several constants can share a metric name but differ by tag, mirroring the
   * distinct {@code LongAdder} fields this enum replaces.
   */
  enum Metric implements StatsDCounterKey {
    API_REQUESTS("apiRequests", "api.requests.total"),
    API_ERRORS("apiErrors", "api.errors.total"),
    // non-OK responses are reported immediately in onSendAttempt with different status tags
    API_RESPONSES_OK("apiResponsesOK", "api.responses.total", "status:200"),

    USER_DROP_ENQUEUED_TRACES(
        "userDropEnqueuedTraces", "queue.enqueued.traces", "priority:user_drop"),
    USER_KEEP_ENQUEUED_TRACES(
        "userKeepEnqueuedTraces", "queue.enqueued.traces", "priority:user_keep"),
    SAMPLER_DROP_ENQUEUED_TRACES(
        "samplerDropEnqueuedTraces", "queue.enqueued.traces", "priority:sampler_drop"),
    SAMPLER_KEEP_ENQUEUED_TRACES(
        "samplerKeepEnqueuedTraces", "queue.enqueued.traces", "priority:sampler_keep"),
    UNSET_PRIORITY_ENQUEUED_TRACES(
        "unsetPriorityEnqueuedTraces", "queue.enqueued.traces", "priority:unset"),

    USER_DROP_DROPPED_TRACES("userDropDroppedTraces", "queue.dropped.traces", "priority:user_drop"),
    USER_KEEP_DROPPED_TRACES("userKeepDroppedTraces", "queue.dropped.traces", "priority:user_keep"),
    SAMPLER_DROP_DROPPED_TRACES(
        "samplerDropDroppedTraces", "queue.dropped.traces", "priority:sampler_drop"),
    SAMPLER_KEEP_DROPPED_TRACES(
        "samplerKeepDroppedTraces", "queue.dropped.traces", "priority:sampler_keep"),
    SERIAL_FAILED_DROPPED_TRACES(
        "serialFailedDroppedTraces", "queue.dropped.traces", "failure:serial"),
    UNSET_PRIORITY_DROPPED_TRACES(
        "unsetPriorityDroppedTraces", "queue.dropped.traces", "priority:unset"),

    USER_DROP_DROPPED_SPANS("userDropDroppedSpans", "queue.dropped.spans", "priority:user_drop"),
    USER_KEEP_DROPPED_SPANS("userKeepDroppedSpans", "queue.dropped.spans", "priority:user_keep"),
    SAMPLER_DROP_DROPPED_SPANS(
        "samplerDropDroppedSpans", "queue.dropped.spans", "priority:sampler_drop"),
    SAMPLER_KEEP_DROPPED_SPANS(
        "samplerKeepDroppedSpans", "queue.dropped.spans", "priority:sampler_keep"),
    SERIAL_FAILED_DROPPED_SPANS(
        "serialFailedDroppedSpans", "queue.dropped.spans", "failure:serial"),
    UNSET_PRIORITY_DROPPED_SPANS(
        "unsetPriorityDroppedSpans", "queue.dropped.spans", "priority:unset"),

    ENQUEUED_SPANS("enqueuedSpans", "queue.enqueued.spans"),
    ENQUEUED_BYTES("enqueuedBytes", "queue.enqueued.bytes"),
    CREATED_TRACES("createdTraces", "trace.pending.created"),
    CREATED_SPANS("createdSpans", "span.pending.created"),
    FINISHED_SPANS("finishedSpans", "span.pending.finished"),
    FLUSHED_TRACES("flushedTraces", "flush.traces.total"),
    FLUSHED_BYTES("flushedBytes", "flush.bytes.total"),
    PARTIAL_TRACES("partialTraces", "queue.partial.traces"),
    PARTIAL_BYTES("partialBytes", "span.flushed.partial"),
    CLIENT_SPANS_WITHOUT_CONTEXT("clientSpansWithoutContext", "span.client.no-context"),

    SINGLE_SPAN_SAMPLED("singleSpanSampled", "span.sampling.sampled", "sampler:single-span"),
    SINGLE_SPAN_UNSAMPLED("singleSpanUnsampled", "span.sampling.unsampled", "sampler:single-span"),

    CAPTURED_CONTINUATIONS("capturedContinuations", "span.continuations.captured"),
    CANCELLED_CONTINUATIONS("cancelledContinuations", "span.continuations.canceled"),
    FINISHED_CONTINUATIONS("finishedContinuations", "span.continuations.finished"),

    ACTIVATED_SCOPES("activatedScopes", "scope.activate.count"),
    CLOSED_SCOPES("closedScopes", "scope.close.count"),
    SCOPE_STACK_OVERFLOW("scopeStackOverflow", "scope.error.stack-overflow"),
    SCOPE_CLOSE_ERRORS("scopeCloseErrors", "scope.close.error"),
    USER_SCOPE_CLOSE_ERRORS("userScopeCloseErrors", "scope.user.close.error"),

    LONG_RUNNING_TRACES_WRITE("longRunningTracesWrite", "long-running.write"),
    LONG_RUNNING_TRACES_DROPPED("longRunningTracesDropped", "long-running.dropped"),
    LONG_RUNNING_TRACES_EXPIRED("longRunningTracesExpired", "long-running.expired"),

    ORG_GUARD_ENFORCE_MISMATCH("orgGuardEnforceMismatch", "org_guard.enforce", "reason:mismatch"),
    ORG_GUARD_ENFORCE_STRICT_MISSING(
        "orgGuardEnforceStrictMissing", "org_guard.enforce", "reason:strict_missing"),

    CLIENT_STATS_PROCESSED_TRACES("clientStatsProcessedTraces", "stats.traces_in"),
    CLIENT_STATS_PROCESSED_SPANS("clientStatsProcessedSpans", "stats.spans_in"),
    CLIENT_STATS_P0_DROPPED_TRACES("clientStatsP0DroppedTraces", "stats.dropped_p0_traces"),
    CLIENT_STATS_P0_DROPPED_SPANS("clientStatsP0DroppedSpans", "stats.dropped_p0_spans"),
    CLIENT_STATS_REQUESTS("clientStatsRequests", "stats.flush_payloads"),
    CLIENT_STATS_ERRORS("clientStatsErrors", "stats.flush_errors"),
    CLIENT_STATS_DOWNGRADES("clientStatsDowngrades", "stats.agent_downgrades"),

    STATS_AGGREGATE_DROPPED(
        "statsAggregateDropped", "stats.dropped_aggregates", "reason:lru_eviction"),
    STATS_INBOX_FULL("statsInboxFull", "stats.dropped_aggregates", "reason:inbox_full"),
    ;

    private final String summaryLabel;
    private final String metricName;
    private final String[] tags;

    Metric(String summaryLabel, String metricName, String... tags) {
      this.summaryLabel = summaryLabel;
      this.metricName = metricName;
      this.tags = tags;
    }

    @Override
    public String getMetricName() {
      return metricName;
    }

    @Override
    public String[] getTags() {
      return tags;
    }

    String getSummaryLabel() {
      return summaryLabel;
    }
  }
}
