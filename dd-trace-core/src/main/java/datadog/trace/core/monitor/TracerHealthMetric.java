package datadog.trace.core.monitor;

import datadog.metrics.api.statsd.StatsDCounterKey;

/**
 * One counter tracked by {@link TracerHealthMetrics}: a dogstatsd metric name + tags, plus the
 * label {@link TracerHealthMetrics#summary()} renders it under. One constant per (counter, tag)
 * combination -- several constants can share a metric name but differ by tag, mirroring the
 * distinct {@code LongAdder} fields this enum replaces.
 */
enum TracerHealthMetric implements StatsDCounterKey {
  API_REQUESTS("api.requests.total", NoTags.NO_TAGS, "apiRequests"),
  API_ERRORS("api.errors.total", NoTags.NO_TAGS, "apiErrors"),
  // non-OK responses are reported immediately in onSendAttempt with different status tags
  API_RESPONSES_OK("api.responses.total", NoTags.STATUS_OK_TAGS, "apiResponsesOK"),

  USER_DROP_ENQUEUED_TRACES(
      "queue.enqueued.traces", NoTags.USER_DROP_TAG, "userDropEnqueuedTraces"),
  USER_KEEP_ENQUEUED_TRACES(
      "queue.enqueued.traces", NoTags.USER_KEEP_TAG, "userKeepEnqueuedTraces"),
  SAMPLER_DROP_ENQUEUED_TRACES(
      "queue.enqueued.traces", NoTags.SAMPLER_DROP_TAG, "samplerDropEnqueuedTraces"),
  SAMPLER_KEEP_ENQUEUED_TRACES(
      "queue.enqueued.traces", NoTags.SAMPLER_KEEP_TAG, "samplerKeepEnqueuedTraces"),
  UNSET_PRIORITY_ENQUEUED_TRACES(
      "queue.enqueued.traces", NoTags.UNSET_TAG, "unsetPriorityEnqueuedTraces"),

  USER_DROP_DROPPED_TRACES("queue.dropped.traces", NoTags.USER_DROP_TAG, "userDropDroppedTraces"),
  USER_KEEP_DROPPED_TRACES("queue.dropped.traces", NoTags.USER_KEEP_TAG, "userKeepDroppedTraces"),
  SAMPLER_DROP_DROPPED_TRACES(
      "queue.dropped.traces", NoTags.SAMPLER_DROP_TAG, "samplerDropDroppedTraces"),
  SAMPLER_KEEP_DROPPED_TRACES(
      "queue.dropped.traces", NoTags.SAMPLER_KEEP_TAG, "samplerKeepDroppedTraces"),
  SERIAL_FAILED_DROPPED_TRACES(
      "queue.dropped.traces", NoTags.SERIAL_FAILED_TAG, "serialFailedDroppedTraces"),
  UNSET_PRIORITY_DROPPED_TRACES(
      "queue.dropped.traces", NoTags.UNSET_TAG, "unsetPriorityDroppedTraces"),

  USER_DROP_DROPPED_SPANS("queue.dropped.spans", NoTags.USER_DROP_TAG, "userDropDroppedSpans"),
  USER_KEEP_DROPPED_SPANS("queue.dropped.spans", NoTags.USER_KEEP_TAG, "userKeepDroppedSpans"),
  SAMPLER_DROP_DROPPED_SPANS(
      "queue.dropped.spans", NoTags.SAMPLER_DROP_TAG, "samplerDropDroppedSpans"),
  SAMPLER_KEEP_DROPPED_SPANS(
      "queue.dropped.spans", NoTags.SAMPLER_KEEP_TAG, "samplerKeepDroppedSpans"),
  SERIAL_FAILED_DROPPED_SPANS(
      "queue.dropped.spans", NoTags.SERIAL_FAILED_TAG, "serialFailedDroppedSpans"),
  UNSET_PRIORITY_DROPPED_SPANS(
      "queue.dropped.spans", NoTags.UNSET_TAG, "unsetPriorityDroppedSpans"),

  ENQUEUED_SPANS("queue.enqueued.spans", NoTags.NO_TAGS, "enqueuedSpans"),
  ENQUEUED_BYTES("queue.enqueued.bytes", NoTags.NO_TAGS, "enqueuedBytes"),
  CREATED_TRACES("trace.pending.created", NoTags.NO_TAGS, "createdTraces"),
  CREATED_SPANS("span.pending.created", NoTags.NO_TAGS, "createdSpans"),
  FINISHED_SPANS("span.pending.finished", NoTags.NO_TAGS, "finishedSpans"),
  FLUSHED_TRACES("flush.traces.total", NoTags.NO_TAGS, "flushedTraces"),
  FLUSHED_BYTES("flush.bytes.total", NoTags.NO_TAGS, "flushedBytes"),
  PARTIAL_TRACES("queue.partial.traces", NoTags.NO_TAGS, "partialTraces"),
  PARTIAL_BYTES("span.flushed.partial", NoTags.NO_TAGS, "partialBytes"),
  CLIENT_SPANS_WITHOUT_CONTEXT(
      "span.client.no-context", NoTags.NO_TAGS, "clientSpansWithoutContext"),

  SINGLE_SPAN_SAMPLED("span.sampling.sampled", NoTags.SINGLE_SPAN_SAMPLER_TAG, "singleSpanSampled"),
  SINGLE_SPAN_UNSAMPLED(
      "span.sampling.unsampled", NoTags.SINGLE_SPAN_SAMPLER_TAG, "singleSpanUnsampled"),

  CAPTURED_CONTINUATIONS("span.continuations.captured", NoTags.NO_TAGS, "capturedContinuations"),
  CANCELLED_CONTINUATIONS("span.continuations.canceled", NoTags.NO_TAGS, "cancelledContinuations"),
  FINISHED_CONTINUATIONS("span.continuations.finished", NoTags.NO_TAGS, "finishedContinuations"),

  ACTIVATED_SCOPES("scope.activate.count", NoTags.NO_TAGS, "activatedScopes"),
  CLOSED_SCOPES("scope.close.count", NoTags.NO_TAGS, "closedScopes"),
  SCOPE_STACK_OVERFLOW("scope.error.stack-overflow", NoTags.NO_TAGS, "scopeStackOverflow"),
  SCOPE_CLOSE_ERRORS("scope.close.error", NoTags.NO_TAGS, "scopeCloseErrors"),
  USER_SCOPE_CLOSE_ERRORS("scope.user.close.error", NoTags.NO_TAGS, "userScopeCloseErrors"),

  LONG_RUNNING_TRACES_WRITE("long-running.write", NoTags.NO_TAGS, "longRunningTracesWrite"),
  LONG_RUNNING_TRACES_DROPPED("long-running.dropped", NoTags.NO_TAGS, "longRunningTracesDropped"),
  LONG_RUNNING_TRACES_EXPIRED("long-running.expired", NoTags.NO_TAGS, "longRunningTracesExpired"),

  // not rendered by summary() -- matches the pre-migration behavior, which never printed these
  ORG_GUARD_ENFORCE_MISMATCH(
      "org_guard.enforce", NoTags.ORG_GUARD_MISMATCH_TAGS, "orgGuardEnforceMismatch", false),
  ORG_GUARD_ENFORCE_STRICT_MISSING(
      "org_guard.enforce",
      NoTags.ORG_GUARD_STRICT_MISSING_TAGS,
      "orgGuardEnforceStrictMissing",
      false),

  CLIENT_STATS_PROCESSED_TRACES("stats.traces_in", NoTags.NO_TAGS, "clientStatsProcessedTraces"),
  CLIENT_STATS_PROCESSED_SPANS("stats.spans_in", NoTags.NO_TAGS, "clientStatsProcessedSpans"),
  CLIENT_STATS_P0_DROPPED_TRACES(
      "stats.dropped_p0_traces", NoTags.NO_TAGS, "clientStatsP0DroppedTraces"),
  CLIENT_STATS_P0_DROPPED_SPANS(
      "stats.dropped_p0_spans", NoTags.NO_TAGS, "clientStatsP0DroppedSpans"),
  CLIENT_STATS_REQUESTS("stats.flush_payloads", NoTags.NO_TAGS, "clientStatsRequests"),
  CLIENT_STATS_ERRORS("stats.flush_errors", NoTags.NO_TAGS, "clientStatsErrors"),
  CLIENT_STATS_DOWNGRADES("stats.agent_downgrades", NoTags.NO_TAGS, "clientStatsDowngrades"),

  STATS_AGGREGATE_DROPPED(
      "stats.dropped_aggregates", NoTags.REASON_LRU_EVICTION_TAG, "statsAggregateDropped"),
  STATS_INBOX_FULL("stats.dropped_aggregates", NoTags.REASON_INBOX_FULL_TAG, "statsInboxFull"),
  ;

  private final String metricName;
  private final String[] tags;
  private final String summaryLabel;
  private final boolean reportedInSummary;

  TracerHealthMetric(String metricName, String[] tags, String summaryLabel) {
    this(metricName, tags, summaryLabel, true);
  }

  TracerHealthMetric(
      String metricName, String[] tags, String summaryLabel, boolean reportedInSummary) {
    this.metricName = metricName;
    this.tags = tags;
    this.summaryLabel = summaryLabel;
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

  /** Tag arrays, namespaced to keep the enum's constant list above readable. */
  private static final class NoTags {
    private static final String[] NO_TAGS = new String[0];
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
