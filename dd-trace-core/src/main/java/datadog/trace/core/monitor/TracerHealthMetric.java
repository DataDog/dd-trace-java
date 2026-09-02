package datadog.trace.core.monitor;

import datadog.metrics.api.statsd.StatsDCounterKey;

/**
 * One counter tracked by {@link TracerHealthMetrics}: a dogstatsd metric name + tags, plus the
 * label {@link TracerHealthMetrics#summary()} renders it under. One constant per (counter, tag)
 * combination -- several constants can share a metric name but differ by tag, mirroring the
 * distinct {@code LongAdder} fields this enum replaces.
 */
enum TracerHealthMetric implements StatsDCounterKey {
  API_REQUESTS("apiRequests", "api.requests.total"),
  API_ERRORS("apiErrors", "api.errors.total"),
  // non-OK responses are reported immediately in onSendAttempt with different status tags
  API_RESPONSES_OK("apiResponsesOK", "api.responses.total", Tags.STATUS_OK_TAGS),

  USER_DROP_ENQUEUED_TRACES("userDropEnqueuedTraces", "queue.enqueued.traces", Tags.USER_DROP_TAG),
  USER_KEEP_ENQUEUED_TRACES("userKeepEnqueuedTraces", "queue.enqueued.traces", Tags.USER_KEEP_TAG),
  SAMPLER_DROP_ENQUEUED_TRACES(
      "samplerDropEnqueuedTraces", "queue.enqueued.traces", Tags.SAMPLER_DROP_TAG),
  SAMPLER_KEEP_ENQUEUED_TRACES(
      "samplerKeepEnqueuedTraces", "queue.enqueued.traces", Tags.SAMPLER_KEEP_TAG),
  UNSET_PRIORITY_ENQUEUED_TRACES(
      "unsetPriorityEnqueuedTraces", "queue.enqueued.traces", Tags.UNSET_TAG),

  USER_DROP_DROPPED_TRACES("userDropDroppedTraces", "queue.dropped.traces", Tags.USER_DROP_TAG),
  USER_KEEP_DROPPED_TRACES("userKeepDroppedTraces", "queue.dropped.traces", Tags.USER_KEEP_TAG),
  SAMPLER_DROP_DROPPED_TRACES(
      "samplerDropDroppedTraces", "queue.dropped.traces", Tags.SAMPLER_DROP_TAG),
  SAMPLER_KEEP_DROPPED_TRACES(
      "samplerKeepDroppedTraces", "queue.dropped.traces", Tags.SAMPLER_KEEP_TAG),
  SERIAL_FAILED_DROPPED_TRACES(
      "serialFailedDroppedTraces", "queue.dropped.traces", Tags.SERIAL_FAILED_TAG),
  UNSET_PRIORITY_DROPPED_TRACES(
      "unsetPriorityDroppedTraces", "queue.dropped.traces", Tags.UNSET_TAG),

  USER_DROP_DROPPED_SPANS("userDropDroppedSpans", "queue.dropped.spans", Tags.USER_DROP_TAG),
  USER_KEEP_DROPPED_SPANS("userKeepDroppedSpans", "queue.dropped.spans", Tags.USER_KEEP_TAG),
  SAMPLER_DROP_DROPPED_SPANS(
      "samplerDropDroppedSpans", "queue.dropped.spans", Tags.SAMPLER_DROP_TAG),
  SAMPLER_KEEP_DROPPED_SPANS(
      "samplerKeepDroppedSpans", "queue.dropped.spans", Tags.SAMPLER_KEEP_TAG),
  SERIAL_FAILED_DROPPED_SPANS(
      "serialFailedDroppedSpans", "queue.dropped.spans", Tags.SERIAL_FAILED_TAG),
  UNSET_PRIORITY_DROPPED_SPANS("unsetPriorityDroppedSpans", "queue.dropped.spans", Tags.UNSET_TAG),

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

  SINGLE_SPAN_SAMPLED("singleSpanSampled", "span.sampling.sampled", Tags.SINGLE_SPAN_SAMPLER_TAG),
  SINGLE_SPAN_UNSAMPLED(
      "singleSpanUnsampled", "span.sampling.unsampled", Tags.SINGLE_SPAN_SAMPLER_TAG),

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

  // not rendered by summary() -- matches the pre-migration behavior, which never printed these
  ORG_GUARD_ENFORCE_MISMATCH(
      "orgGuardEnforceMismatch", "org_guard.enforce", Tags.ORG_GUARD_MISMATCH_TAGS, false),
  ORG_GUARD_ENFORCE_STRICT_MISSING(
      "orgGuardEnforceStrictMissing",
      "org_guard.enforce",
      Tags.ORG_GUARD_STRICT_MISSING_TAGS,
      false),

  CLIENT_STATS_PROCESSED_TRACES("clientStatsProcessedTraces", "stats.traces_in"),
  CLIENT_STATS_PROCESSED_SPANS("clientStatsProcessedSpans", "stats.spans_in"),
  CLIENT_STATS_P0_DROPPED_TRACES("clientStatsP0DroppedTraces", "stats.dropped_p0_traces"),
  CLIENT_STATS_P0_DROPPED_SPANS("clientStatsP0DroppedSpans", "stats.dropped_p0_spans"),
  CLIENT_STATS_REQUESTS("clientStatsRequests", "stats.flush_payloads"),
  CLIENT_STATS_ERRORS("clientStatsErrors", "stats.flush_errors"),
  CLIENT_STATS_DOWNGRADES("clientStatsDowngrades", "stats.agent_downgrades"),

  STATS_AGGREGATE_DROPPED(
      "statsAggregateDropped", "stats.dropped_aggregates", Tags.REASON_LRU_EVICTION_TAG),
  STATS_INBOX_FULL("statsInboxFull", "stats.dropped_aggregates", Tags.REASON_INBOX_FULL_TAG),
  ;

  private final String summaryLabel;
  private final String metricName;
  private final String[] tags;
  private final boolean reportedInSummary;

  TracerHealthMetric(String summaryLabel, String metricName) {
    this(summaryLabel, metricName, new String[0]);
  }

  TracerHealthMetric(String summaryLabel, String metricName, String[] tags) {
    this(summaryLabel, metricName, tags, true);
  }

  TracerHealthMetric(
      String summaryLabel, String metricName, String[] tags, boolean reportedInSummary) {
    this.summaryLabel = summaryLabel;
    this.metricName = metricName;
    this.tags = tags;
    this.reportedInSummary = reportedInSummary;
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

  boolean isReportedInSummary() {
    return reportedInSummary;
  }

  /** Tag arrays shared by more than one constant above, namespaced to keep that list readable. */
  private static final class Tags {
    private static final String[] USER_DROP_TAG = new String[] {"priority:user_drop"};
    private static final String[] USER_KEEP_TAG = new String[] {"priority:user_keep"};
    private static final String[] SAMPLER_DROP_TAG = new String[] {"priority:sampler_drop"};
    private static final String[] SAMPLER_KEEP_TAG = new String[] {"priority:sampler_keep"};
    private static final String[] SERIAL_FAILED_TAG = new String[] {"failure:serial"};
    private static final String[] UNSET_TAG = new String[] {"priority:unset"};
    private static final String[] SINGLE_SPAN_SAMPLER_TAG = new String[] {"sampler:single-span"};
    private static final String[] REASON_LRU_EVICTION_TAG = new String[] {"reason:lru_eviction"};
    private static final String[] REASON_INBOX_FULL_TAG = new String[] {"reason:inbox_full"};
    private static final String[] ORG_GUARD_MISMATCH_TAGS = new String[] {"reason:mismatch"};
    private static final String[] ORG_GUARD_STRICT_MISSING_TAGS =
        new String[] {"reason:strict_missing"};
    private static final String[] STATUS_OK_TAGS = new String[] {"status:200"};
  }
}
