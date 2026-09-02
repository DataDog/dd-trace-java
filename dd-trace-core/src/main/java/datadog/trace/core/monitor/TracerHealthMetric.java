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
  SERIAL_FAILED_DROPPED_SPANS("serialFailedDroppedSpans", "queue.dropped.spans", "failure:serial"),
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

  TracerHealthMetric(String summaryLabel, String metricName, String... tags) {
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
