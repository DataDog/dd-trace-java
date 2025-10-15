package datadog.trace.core.monitor;

import static datadog.trace.api.DDSpanId.ZERO;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.StatsDClient;
import datadog.trace.api.cache.RadixTreeCache;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracerHealthMetrics extends HealthMetrics implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(TracerHealthMetrics.class);

  private static final IntFunction<String[]> STATUS_TAGS =
      httpStatus -> new String[] {"status:" + httpStatus};

  private static final String[] NO_TAGS = new String[0];
  private static final String[] STATUS_OK_TAGS = STATUS_TAGS.apply(200);
  private final RadixTreeCache<String[]> statusTagsCache =
      new RadixTreeCache<>(16, 32, STATUS_TAGS, 200, 400);

  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile AgentTaskScheduler.Scheduled<TracerHealthMetrics> cancellation;

  private final LongAdder apiRequests = new LongAdder();
  private final LongAdder apiErrors = new LongAdder();
  private final LongAdder apiResponsesOK = new LongAdder();

  private final LongAdder userDropEnqueuedTraces = new LongAdder();
  private final LongAdder userKeepEnqueuedTraces = new LongAdder();
  private final LongAdder samplerDropEnqueuedTraces = new LongAdder();
  private final LongAdder samplerKeepEnqueuedTraces = new LongAdder();
  private final LongAdder unsetPriorityEnqueuedTraces = new LongAdder();

  private final LongAdder userDropDroppedTraces = new LongAdder();
  private final LongAdder userKeepDroppedTraces = new LongAdder();
  private final LongAdder samplerDropDroppedTraces = new LongAdder();
  private final LongAdder samplerKeepDroppedTraces = new LongAdder();
  private final LongAdder serialFailedDroppedTraces = new LongAdder();
  private final LongAdder unsetPriorityDroppedTraces = new LongAdder();

  private final LongAdder userDropDroppedSpans = new LongAdder();
  private final LongAdder userKeepDroppedSpans = new LongAdder();
  private final LongAdder samplerDropDroppedSpans = new LongAdder();
  private final LongAdder samplerKeepDroppedSpans = new LongAdder();
  private final LongAdder serialFailedDroppedSpans = new LongAdder();
  private final LongAdder unsetPriorityDroppedSpans = new LongAdder();

  private final LongAdder enqueuedSpans = new LongAdder();
  private final LongAdder enqueuedBytes = new LongAdder();
  private final LongAdder createdTraces = new LongAdder();
  private final LongAdder createdSpans = new LongAdder();
  private final LongAdder finishedSpans = new LongAdder();
  private final LongAdder flushedTraces = new LongAdder();
  private final LongAdder flushedBytes = new LongAdder();
  private final LongAdder partialTraces = new LongAdder();
  private final LongAdder partialBytes = new LongAdder();
  private final LongAdder clientSpansWithoutContext = new LongAdder();

  private final LongAdder singleSpanSampled = new LongAdder();
  private final LongAdder singleSpanUnsampled = new LongAdder();

  private final LongAdder capturedContinuations = new LongAdder();
  private final LongAdder cancelledContinuations = new LongAdder();
  private final LongAdder finishedContinuations = new LongAdder();

  private final LongAdder activatedScopes = new LongAdder();
  private final LongAdder closedScopes = new LongAdder();
  private final LongAdder scopeStackOverflow = new LongAdder();
  private final LongAdder scopeCloseErrors = new LongAdder();
  private final LongAdder userScopeCloseErrors = new LongAdder();

  private final LongAdder longRunningTracesWrite = new LongAdder();
  private final LongAdder longRunningTracesDropped = new LongAdder();
  private final LongAdder longRunningTracesExpired = new LongAdder();

  private final LongAdder clientStatsProcessedSpans = new LongAdder();
  private final LongAdder clientStatsProcessedTraces = new LongAdder();
  private final LongAdder clientStatsP0DroppedSpans = new LongAdder();
  private final LongAdder clientStatsP0DroppedTraces = new LongAdder();
  private final LongAdder clientStatsRequests = new LongAdder();
  private final LongAdder clientStatsErrors = new LongAdder();
  private final LongAdder clientStatsDowngrades = new LongAdder();

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
        userDropEnqueuedTraces.increment();
        break;
      case USER_KEEP:
        userKeepEnqueuedTraces.increment();
        break;
      case SAMPLER_DROP:
        samplerDropEnqueuedTraces.increment();
        break;
      case SAMPLER_KEEP:
        samplerKeepEnqueuedTraces.increment();
        break;
      default:
        unsetPriorityEnqueuedTraces.increment();
    }
    enqueuedSpans.add(trace.size());
    checkForClientSpansWithoutContext(trace);
  }

  private void checkForClientSpansWithoutContext(final List<DDSpan> trace) {
    for (DDSpan span : trace) {
      if (span != null && span.getParentId() == ZERO) {
        String spanKind = span.getTag(SPAN_KIND, "undefined");
        if (SPAN_KIND_CLIENT.equals(spanKind)) {
          this.clientSpansWithoutContext.increment();
        }
      }
    }
  }

  @Override
  public void onFailedPublish(final int samplingPriority, final int spanCount) {
    switch (samplingPriority) {
      case USER_DROP:
        userDropDroppedSpans.add(spanCount);
        userDropDroppedTraces.increment();
        break;
      case USER_KEEP:
        userKeepDroppedSpans.add(spanCount);
        userKeepDroppedTraces.increment();
        break;
      case SAMPLER_DROP:
        samplerDropDroppedSpans.add(spanCount);
        samplerDropDroppedTraces.increment();
        break;
      case SAMPLER_KEEP:
        samplerKeepDroppedSpans.add(spanCount);
        samplerKeepDroppedTraces.increment();
        break;
      default:
        unsetPriorityDroppedSpans.add(spanCount);
        unsetPriorityDroppedTraces.increment();
    }
  }

  @Override
  public void onPartialPublish(final int numberOfDroppedSpans) {
    partialTraces.increment();
    samplerDropDroppedSpans.add(numberOfDroppedSpans);
  }

  @Override
  public void onScheduleFlush(final boolean previousIncomplete) {
    // not recorded
  }

  @Override
  public void onFlush(final boolean early) {}

  @Override
  public void onPartialFlush(final int sizeInBytes) {
    partialBytes.add(sizeInBytes);
  }

  @Override
  public void onSingleSpanSample() {
    singleSpanSampled.increment();
  }

  @Override
  public void onSingleSpanUnsampled() {
    singleSpanUnsampled.increment();
  }

  @Override
  public void onSerialize(final int serializedSizeInBytes) {
    // DQH - Because of Java tracer's 2 phase acceptance and serialization scheme, this doesn't
    // map precisely
    enqueuedBytes.add(serializedSizeInBytes);
  }

  @Override
  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {
    if (trace != null) {
      serialFailedDroppedTraces.increment();
      serialFailedDroppedSpans.add(trace.size());
    }
  }

  @Override
  public void onCreateSpan() {
    createdSpans.increment();
  }

  @Override
  public void onFinishSpan() {
    finishedSpans.increment();
  }

  @Override
  public void onCreateTrace() {
    createdTraces.increment();
  }

  @Override
  public void onScopeCloseError(boolean manual) {
    scopeCloseErrors.increment();
    if (manual) {
      userScopeCloseErrors.increment();
    }
  }

  @Override
  public void onCaptureContinuation() {
    capturedContinuations.increment();
  }

  @Override
  public void onCancelContinuation() {
    cancelledContinuations.increment();
  }

  @Override
  public void onFinishContinuation() {
    finishedContinuations.increment();
  }

  @Override
  public void onActivateScope() {
    activatedScopes.increment();
  }

  @Override
  public void onCloseScope() {
    closedScopes.increment();
  }

  @Override
  public void onScopeStackOverflow() {
    scopeStackOverflow.increment();
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
    longRunningTracesWrite.add(write);
    longRunningTracesDropped.add(dropped);
    longRunningTracesExpired.add(expired);
  }

  private void onSendAttempt(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    apiRequests.increment();
    flushedTraces.add(traceCount);
    // TODO: missing queue.spans (# of spans being sent)
    flushedBytes.add(sizeInBytes);

    if (response.exception().isPresent()) {
      // covers communication errors -- both not receiving a response or
      // receiving malformed response (even when otherwise successful)
      apiErrors.increment();
    }

    int status = response.status().orElse(0);
    if (status != 0) {
      if (200 == status) {
        apiResponsesOK.increment();
      } else {
        statsd.incrementCounter("api.responses.total", statusTagsCache.get(status));
      }
    }
  }

  @Override
  public void onClientStatTraceComputed(int countedSpans, int totalSpans, boolean dropped) {
    clientStatsProcessedTraces.increment();
    clientStatsProcessedSpans.add(countedSpans);
    if (dropped) {
      clientStatsP0DroppedTraces.increment();
      clientStatsP0DroppedSpans.add(totalSpans);
    }
  }

  @Override
  public void onClientStatPayloadSent() {
    clientStatsRequests.increment();
  }

  @Override
  public void onClientStatDowngraded() {
    clientStatsDowngrades.increment();
  }

  @Override
  public void onClientStatErrorReceived() {
    clientStatsErrors.increment();
  }

  @Override
  public void close() {
    if (null != cancellation) {
      cancellation.cancel();
    }
  }

  private static class Flush implements AgentTaskScheduler.Task<TracerHealthMetrics> {

    private static final String[] USER_DROP_TAG = new String[] {"priority:user_drop"};
    private static final String[] USER_KEEP_TAG = new String[] {"priority:user_keep"};
    private static final String[] SAMPLER_DROP_TAG = new String[] {"priority:sampler_drop"};
    private static final String[] SAMPLER_KEEP_TAG = new String[] {"priority:sampler_keep"};
    private static final String[] SERIAL_FAILED_TAG = new String[] {"failure:serial"};
    private static final String[] UNSET_TAG = new String[] {"priority:unset"};
    private static final String[] SINGLE_SPAN_SAMPLER = new String[] {"sampler:single-span"};

    private final long[] previousCounts = new long[50];
    private int countIndex;

    @Override
    public void run(TracerHealthMetrics target) {
      countIndex = -1; // reposition so _next_ value is 0
      try {

        reportIfChanged(target.statsd, "api.requests.total", target.apiRequests, NO_TAGS);
        reportIfChanged(target.statsd, "api.errors.total", target.apiErrors, NO_TAGS);
        // non-OK responses are reported immediately in onSendAttempt with different status tags
        reportIfChanged(
            target.statsd, "api.responses.total", target.apiResponsesOK, STATUS_OK_TAGS);

        reportIfChanged(
            target.statsd, "queue.enqueued.traces", target.userDropEnqueuedTraces, USER_DROP_TAG);
        reportIfChanged(
            target.statsd, "queue.enqueued.traces", target.userKeepEnqueuedTraces, USER_KEEP_TAG);
        reportIfChanged(
            target.statsd,
            "queue.enqueued.traces",
            target.samplerDropEnqueuedTraces,
            SAMPLER_DROP_TAG);
        reportIfChanged(
            target.statsd,
            "queue.enqueued.traces",
            target.samplerKeepEnqueuedTraces,
            SAMPLER_KEEP_TAG);
        reportIfChanged(
            target.statsd, "queue.enqueued.traces", target.unsetPriorityEnqueuedTraces, UNSET_TAG);

        reportIfChanged(
            target.statsd, "queue.dropped.traces", target.userDropDroppedTraces, USER_DROP_TAG);
        reportIfChanged(
            target.statsd, "queue.dropped.traces", target.userKeepDroppedTraces, USER_KEEP_TAG);
        reportIfChanged(
            target.statsd,
            "queue.dropped.traces",
            target.samplerDropDroppedTraces,
            SAMPLER_DROP_TAG);
        reportIfChanged(
            target.statsd,
            "queue.dropped.traces",
            target.samplerKeepDroppedTraces,
            SAMPLER_KEEP_TAG);
        reportIfChanged(
            target.statsd,
            "queue.dropped.traces",
            target.serialFailedDroppedTraces,
            SERIAL_FAILED_TAG);
        reportIfChanged(
            target.statsd, "queue.dropped.traces", target.unsetPriorityDroppedTraces, UNSET_TAG);

        reportIfChanged(
            target.statsd, "queue.dropped.spans", target.userDropDroppedSpans, USER_DROP_TAG);
        reportIfChanged(
            target.statsd, "queue.dropped.spans", target.userKeepDroppedSpans, USER_KEEP_TAG);
        reportIfChanged(
            target.statsd, "queue.dropped.spans", target.samplerDropDroppedSpans, SAMPLER_DROP_TAG);
        reportIfChanged(
            target.statsd, "queue.dropped.spans", target.samplerKeepDroppedSpans, SAMPLER_KEEP_TAG);
        reportIfChanged(
            target.statsd,
            "queue.dropped.spans",
            target.serialFailedDroppedSpans,
            SERIAL_FAILED_TAG);
        reportIfChanged(
            target.statsd, "queue.dropped.spans", target.unsetPriorityDroppedSpans, UNSET_TAG);

        reportIfChanged(target.statsd, "queue.enqueued.spans", target.enqueuedSpans, NO_TAGS);
        reportIfChanged(target.statsd, "queue.enqueued.bytes", target.enqueuedBytes, NO_TAGS);
        reportIfChanged(target.statsd, "trace.pending.created", target.createdTraces, NO_TAGS);
        reportIfChanged(target.statsd, "span.pending.created", target.createdSpans, NO_TAGS);
        reportIfChanged(target.statsd, "span.pending.finished", target.finishedSpans, NO_TAGS);
        reportIfChanged(target.statsd, "flush.traces.total", target.flushedTraces, NO_TAGS);
        reportIfChanged(target.statsd, "flush.bytes.total", target.flushedBytes, NO_TAGS);
        reportIfChanged(target.statsd, "queue.partial.traces", target.partialTraces, NO_TAGS);
        reportIfChanged(target.statsd, "span.flushed.partial", target.partialBytes, NO_TAGS);
        reportIfChanged(
            target.statsd, "span.client.no-context", target.clientSpansWithoutContext, NO_TAGS);

        reportIfChanged(
            target.statsd, "span.sampling.sampled", target.singleSpanSampled, SINGLE_SPAN_SAMPLER);
        reportIfChanged(
            target.statsd,
            "span.sampling.unsampled",
            target.singleSpanUnsampled,
            SINGLE_SPAN_SAMPLER);

        reportIfChanged(
            target.statsd, "span.continuations.captured", target.capturedContinuations, NO_TAGS);
        reportIfChanged(
            target.statsd, "span.continuations.canceled", target.cancelledContinuations, NO_TAGS);
        reportIfChanged(
            target.statsd, "span.continuations.finished", target.finishedContinuations, NO_TAGS);

        reportIfChanged(target.statsd, "scope.activate.count", target.activatedScopes, NO_TAGS);
        reportIfChanged(target.statsd, "scope.close.count", target.closedScopes, NO_TAGS);
        reportIfChanged(
            target.statsd, "scope.error.stack-overflow", target.scopeStackOverflow, NO_TAGS);
        reportIfChanged(target.statsd, "scope.close.error", target.scopeCloseErrors, NO_TAGS);
        reportIfChanged(
            target.statsd, "scope.user.close.error", target.userScopeCloseErrors, NO_TAGS);

        reportIfChanged(
            target.statsd, "long-running.write", target.longRunningTracesWrite, NO_TAGS);
        reportIfChanged(
            target.statsd, "long-running.dropped", target.longRunningTracesDropped, NO_TAGS);
        reportIfChanged(
            target.statsd, "long-running.expired", target.longRunningTracesExpired, NO_TAGS);

        reportIfChanged(
            target.statsd, "stats.traces_in", target.clientStatsProcessedTraces, NO_TAGS);
        reportIfChanged(target.statsd, "stats.spans_in", target.clientStatsProcessedSpans, NO_TAGS);
        reportIfChanged(
            target.statsd, "stats.dropped_p0_traces", target.clientStatsP0DroppedTraces, NO_TAGS);
        reportIfChanged(
            target.statsd, "stats.dropped_p0_spans", target.clientStatsP0DroppedSpans, NO_TAGS);
        reportIfChanged(target.statsd, "stats.flush_payloads", target.clientStatsRequests, NO_TAGS);
        reportIfChanged(target.statsd, "stats.flush_errors", target.clientStatsErrors, NO_TAGS);
        reportIfChanged(
            target.statsd, "stats.agent_downgrades", target.clientStatsDowngrades, NO_TAGS);

      } catch (ArrayIndexOutOfBoundsException e) {
        log.warn(
            "previousCounts array needs resizing to at least {}, was {}",
            countIndex + 1,
            previousCounts.length);
      }
    }

    private void reportIfChanged(
        StatsDClient statsDClient, String aspect, LongAdder counter, String[] tags) {
      long count = counter.sum();
      long delta = count - previousCounts[++countIndex];
      if (delta > 0) {
        statsDClient.count(aspect, delta, tags);
        previousCounts[countIndex] = count;
      }
    }
  }

  @Override
  public String summary() {
    return "apiRequests="
        + apiRequests.sum()
        + "\napiErrors="
        + apiErrors.sum()
        + "\napiResponsesOK="
        + apiResponsesOK.sum()
        + "\n"
        + "\nuserDropEnqueuedTraces="
        + userDropEnqueuedTraces.sum()
        + "\nuserKeepEnqueuedTraces="
        + userKeepEnqueuedTraces.sum()
        + "\nsamplerDropEnqueuedTraces="
        + samplerDropEnqueuedTraces.sum()
        + "\nsamplerKeepEnqueuedTraces="
        + samplerKeepEnqueuedTraces.sum()
        + "\nunsetPriorityEnqueuedTraces="
        + unsetPriorityEnqueuedTraces.sum()
        + "\n"
        + "\nuserDropDroppedTraces="
        + userDropDroppedTraces.sum()
        + "\nuserKeepDroppedTraces="
        + userKeepDroppedTraces.sum()
        + "\nsamplerDropDroppedTraces="
        + samplerDropDroppedTraces.sum()
        + "\nsamplerKeepDroppedTraces="
        + samplerKeepDroppedTraces.sum()
        + "\nserialFailedDroppedTraces="
        + serialFailedDroppedTraces.sum()
        + "\nunsetPriorityDroppedTraces="
        + unsetPriorityDroppedTraces.sum()
        + "\n"
        + "\nuserDropDroppedSpans="
        + userDropDroppedSpans.sum()
        + "\nuserKeepDroppedSpans="
        + userKeepDroppedSpans.sum()
        + "\nsamplerDropDroppedSpans="
        + samplerDropDroppedSpans.sum()
        + "\nsamplerKeepDroppedSpans="
        + samplerKeepDroppedSpans.sum()
        + "\nserialFailedDroppedSpans="
        + serialFailedDroppedSpans.sum()
        + "\nunsetPriorityDroppedSpans="
        + unsetPriorityDroppedSpans.sum()
        + "\n"
        + "\nenqueuedSpans="
        + enqueuedSpans.sum()
        + "\nenqueuedBytes="
        + enqueuedBytes.sum()
        + "\ncreatedTraces="
        + createdTraces.sum()
        + "\ncreatedSpans="
        + createdSpans.sum()
        + "\nfinishedSpans="
        + finishedSpans.sum()
        + "\nflushedTraces="
        + flushedTraces.sum()
        + "\nflushedBytes="
        + flushedBytes.sum()
        + "\npartialTraces="
        + partialTraces.sum()
        + "\npartialBytes="
        + partialBytes.sum()
        + "\n"
        + "\nclientSpansWithoutContext="
        + clientSpansWithoutContext.sum()
        + "\n"
        + "\nsingleSpanSampled="
        + singleSpanSampled.sum()
        + "\nsingleSpanUnsampled="
        + singleSpanUnsampled.sum()
        + "\n"
        + "\ncapturedContinuations="
        + capturedContinuations.sum()
        + "\ncancelledContinuations="
        + cancelledContinuations.sum()
        + "\nfinishedContinuations="
        + finishedContinuations.sum()
        + "\n"
        + "\nactivatedScopes="
        + activatedScopes.sum()
        + "\nclosedScopes="
        + closedScopes.sum()
        + "\nscopeStackOverflow="
        + scopeStackOverflow.sum()
        + "\nscopeCloseErrors="
        + scopeCloseErrors.sum()
        + "\nuserScopeCloseErrors="
        + userScopeCloseErrors.sum()
        + "\n"
        + "\nlongRunningTracesWrite="
        + longRunningTracesWrite.sum()
        + "\nlongRunningTracesDropped="
        + longRunningTracesDropped.sum()
        + "\nlongRunningTracesExpired="
        + longRunningTracesExpired.sum()
        + "\n"
        + "\nclientStatsRequests="
        + clientStatsRequests.sum()
        + "\nclientStatsErrors="
        + clientStatsErrors.sum()
        + "\nclientStatsDowngrades="
        + clientStatsDowngrades.sum()
        + "\nclientStatsP0DroppedSpans="
        + clientStatsP0DroppedSpans.sum()
        + "\nclientStatsP0DroppedTraces="
        + clientStatsP0DroppedTraces.sum()
        + "\nclientStatsProcessedSpans="
        + clientStatsProcessedSpans.sum()
        + "\nclientStatsProcessedTraces="
        + clientStatsProcessedTraces.sum();
  }
}
