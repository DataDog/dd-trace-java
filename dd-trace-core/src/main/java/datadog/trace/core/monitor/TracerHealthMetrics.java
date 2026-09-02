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

  private final Accumulator<TracerHealthMetric> counters =
      Accumulator.of(TracerHealthMetric.values());
  private volatile Accumulator.Counts<TracerHealthMetric> storedTotal = counters.sum();

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
    final TracerHealthMetric enqueuedTracesMetric;
    switch (samplingPriority) {
      case USER_DROP:
        enqueuedTracesMetric = TracerHealthMetric.USER_DROP_ENQUEUED_TRACES;
        break;
      case USER_KEEP:
        enqueuedTracesMetric = TracerHealthMetric.USER_KEEP_ENQUEUED_TRACES;
        break;
      case SAMPLER_DROP:
        enqueuedTracesMetric = TracerHealthMetric.SAMPLER_DROP_ENQUEUED_TRACES;
        break;
      case SAMPLER_KEEP:
        enqueuedTracesMetric = TracerHealthMetric.SAMPLER_KEEP_ENQUEUED_TRACES;
        break;
      default:
        enqueuedTracesMetric = TracerHealthMetric.UNSET_PRIORITY_ENQUEUED_TRACES;
    }
    counters.update(
        stripe -> {
          stripe.inc(enqueuedTracesMetric);
          stripe.add(TracerHealthMetric.ENQUEUED_SPANS, trace.size());
        });
    checkForClientSpansWithoutContext(trace);
  }

  private void checkForClientSpansWithoutContext(final List<DDSpan> trace) {
    for (DDSpan span : trace) {
      if (span != null && span.getParentId() == ZERO) {
        String spanKind = span.getTag(SPAN_KIND, "undefined");
        if (SPAN_KIND_CLIENT.equals(spanKind)) {
          counters.inc(TracerHealthMetric.CLIENT_SPANS_WITHOUT_CONTEXT);
        }
      }
    }
  }

  @Override
  public void onFailedPublish(final int samplingPriority, final int spanCount) {
    final TracerHealthMetric droppedSpansMetric;
    final TracerHealthMetric droppedTracesMetric;
    switch (samplingPriority) {
      case USER_DROP:
        droppedSpansMetric = TracerHealthMetric.USER_DROP_DROPPED_SPANS;
        droppedTracesMetric = TracerHealthMetric.USER_DROP_DROPPED_TRACES;
        break;
      case USER_KEEP:
        droppedSpansMetric = TracerHealthMetric.USER_KEEP_DROPPED_SPANS;
        droppedTracesMetric = TracerHealthMetric.USER_KEEP_DROPPED_TRACES;
        break;
      case SAMPLER_DROP:
        droppedSpansMetric = TracerHealthMetric.SAMPLER_DROP_DROPPED_SPANS;
        droppedTracesMetric = TracerHealthMetric.SAMPLER_DROP_DROPPED_TRACES;
        break;
      case SAMPLER_KEEP:
        droppedSpansMetric = TracerHealthMetric.SAMPLER_KEEP_DROPPED_SPANS;
        droppedTracesMetric = TracerHealthMetric.SAMPLER_KEEP_DROPPED_TRACES;
        break;
      default:
        droppedSpansMetric = TracerHealthMetric.UNSET_PRIORITY_DROPPED_SPANS;
        droppedTracesMetric = TracerHealthMetric.UNSET_PRIORITY_DROPPED_TRACES;
    }
    counters.update(
        stripe -> {
          stripe.add(droppedSpansMetric, spanCount);
          stripe.inc(droppedTracesMetric);
        });
  }

  @Override
  public void onPartialPublish(final int numberOfDroppedSpans) {
    counters.update(
        stripe -> {
          stripe.inc(TracerHealthMetric.PARTIAL_TRACES);
          stripe.add(TracerHealthMetric.SAMPLER_DROP_DROPPED_SPANS, numberOfDroppedSpans);
        });
  }

  @Override
  public void onScheduleFlush(final boolean previousIncomplete) {
    // not recorded
  }

  @Override
  public void onFlush(final boolean early) {}

  @Override
  public void onPartialFlush(final int sizeInBytes) {
    counters.add(TracerHealthMetric.PARTIAL_BYTES, sizeInBytes);
  }

  @Override
  public void onSingleSpanSample() {
    counters.inc(TracerHealthMetric.SINGLE_SPAN_SAMPLED);
  }

  @Override
  public void onSingleSpanUnsampled() {
    counters.inc(TracerHealthMetric.SINGLE_SPAN_UNSAMPLED);
  }

  @Override
  public void onSerialize(final int serializedSizeInBytes) {
    // DQH - Because of Java tracer's 2 phase acceptance and serialization scheme, this doesn't
    // map precisely
    counters.add(TracerHealthMetric.ENQUEUED_BYTES, serializedSizeInBytes);
  }

  @Override
  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {
    if (trace != null) {
      counters.update(
          stripe -> {
            stripe.inc(TracerHealthMetric.SERIAL_FAILED_DROPPED_TRACES);
            stripe.add(TracerHealthMetric.SERIAL_FAILED_DROPPED_SPANS, trace.size());
          });
    }
  }

  @Override
  public void onCreateSpan() {
    counters.inc(TracerHealthMetric.CREATED_SPANS);
  }

  @Override
  public void onFinishSpan() {
    counters.inc(TracerHealthMetric.FINISHED_SPANS);
  }

  @Override
  public void onCreateTrace() {
    counters.inc(TracerHealthMetric.CREATED_TRACES);
  }

  @Override
  public void onScopeCloseError(boolean manual) {
    if (manual) {
      counters.update(
          stripe -> {
            stripe.inc(TracerHealthMetric.SCOPE_CLOSE_ERRORS);
            stripe.inc(TracerHealthMetric.USER_SCOPE_CLOSE_ERRORS);
          });
    } else {
      counters.inc(TracerHealthMetric.SCOPE_CLOSE_ERRORS);
    }
  }

  @Override
  public void onCaptureContinuation() {
    counters.inc(TracerHealthMetric.CAPTURED_CONTINUATIONS);
  }

  @Override
  public void onCancelContinuation() {
    counters.inc(TracerHealthMetric.CANCELLED_CONTINUATIONS);
  }

  @Override
  public void onFinishContinuation() {
    counters.inc(TracerHealthMetric.FINISHED_CONTINUATIONS);
  }

  @Override
  public void onActivateScope() {
    counters.inc(TracerHealthMetric.ACTIVATED_SCOPES);
  }

  @Override
  public void onCloseScope() {
    counters.inc(TracerHealthMetric.CLOSED_SCOPES);
  }

  @Override
  public void onScopeStackOverflow() {
    counters.inc(TracerHealthMetric.SCOPE_STACK_OVERFLOW);
  }

  @Override
  public void onOrgGuardEnforce(OrgGuard.Reason reason) {
    switch (reason) {
      case MISMATCH:
        counters.inc(TracerHealthMetric.ORG_GUARD_ENFORCE_MISMATCH);
        break;
      case STRICT_MISSING:
        counters.inc(TracerHealthMetric.ORG_GUARD_ENFORCE_STRICT_MISSING);
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
    counters.update(
        stripe -> {
          stripe.add(TracerHealthMetric.LONG_RUNNING_TRACES_WRITE, write);
          stripe.add(TracerHealthMetric.LONG_RUNNING_TRACES_DROPPED, dropped);
          stripe.add(TracerHealthMetric.LONG_RUNNING_TRACES_EXPIRED, expired);
        });
  }

  private void onSendAttempt(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    final int status = response.status().orElse(0);
    counters.update(
        stripe -> {
          stripe.inc(TracerHealthMetric.API_REQUESTS);
          stripe.add(TracerHealthMetric.FLUSHED_TRACES, traceCount);
          // TODO: missing queue.spans (# of spans being sent)
          stripe.add(TracerHealthMetric.FLUSHED_BYTES, sizeInBytes);

          if (response.exception().isPresent()) {
            // covers communication errors -- both not receiving a response or
            // receiving malformed response (even when otherwise successful)
            stripe.inc(TracerHealthMetric.API_ERRORS);
          }

          if (200 == status) {
            stripe.inc(TracerHealthMetric.API_RESPONSES_OK);
          }
        });

    if (status != 0 && 200 != status) {
      statsd.incrementCounter("api.responses.total", statusTagsCache.get(status));
    }
  }

  @Override
  public void onClientStatTraceComputed(int countedSpans, int totalSpans, boolean dropped) {
    counters.update(
        stripe -> {
          stripe.inc(TracerHealthMetric.CLIENT_STATS_PROCESSED_TRACES);
          stripe.add(TracerHealthMetric.CLIENT_STATS_PROCESSED_SPANS, countedSpans);
          if (dropped) {
            stripe.inc(TracerHealthMetric.CLIENT_STATS_P0_DROPPED_TRACES);
            stripe.add(TracerHealthMetric.CLIENT_STATS_P0_DROPPED_SPANS, totalSpans);
          }
        });
  }

  @Override
  public void onClientStatPayloadSent() {
    counters.inc(TracerHealthMetric.CLIENT_STATS_REQUESTS);
  }

  @Override
  public void onClientStatDowngraded() {
    counters.inc(TracerHealthMetric.CLIENT_STATS_DOWNGRADES);
  }

  @Override
  public void onClientStatErrorReceived() {
    counters.inc(TracerHealthMetric.CLIENT_STATS_ERRORS);
  }

  @Override
  public void onStatsAggregateDropped() {
    counters.inc(TracerHealthMetric.STATS_AGGREGATE_DROPPED);
    statsd.count("datadog.tracer.stats.collapsed_spans", 1, COLLAPSED_WHOLE_KEY_TAGS);
  }

  @Override
  public void onStatsInboxFull() {
    counters.inc(TracerHealthMetric.STATS_INBOX_FULL);
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
      Accumulator.Counts<TracerHealthMetric> delta = target.counters.accumulateAndReset();
      StatsDCountReporter.report(target.statsd, TracerHealthMetric.values(), delta::get);
      target.storedTotal = target.storedTotal.plus(delta);
    }
  }

  @Override
  public String summary() {
    Accumulator.Counts<TracerHealthMetric> live = storedTotal.plus(counters.sum());
    StringBuilder summary = new StringBuilder();
    for (TracerHealthMetric metric : TracerHealthMetric.values()) {
      if (!metric.isReportedInSummary()) {
        continue;
      }
      if (summary.length() > 0) {
        summary.append('\n');
      }
      summary.append(metric.getSummaryLabel()).append('=').append(live.get(metric));
    }
    return summary.toString();
  }
}
