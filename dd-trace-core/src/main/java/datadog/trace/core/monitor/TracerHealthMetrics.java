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
import java.util.function.IntFunction;
import org.jctools.counters.CountersFactory;
import org.jctools.counters.FixedSizeStripedLongCounter;
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

  private final FixedSizeStripedLongCounter apiRequests =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter apiErrors =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter apiResponsesOK =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter userDropEnqueuedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter userKeepEnqueuedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter samplerDropEnqueuedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter samplerKeepEnqueuedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter unsetPriorityEnqueuedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter userDropDroppedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter userKeepDroppedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter samplerDropDroppedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter samplerKeepDroppedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter serialFailedDroppedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter unsetPriorityDroppedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter userDropDroppedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter userKeepDroppedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter samplerDropDroppedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter samplerKeepDroppedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter serialFailedDroppedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter unsetPriorityDroppedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter enqueuedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter enqueuedBytes =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter createdTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter createdSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter finishedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter flushedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter flushedBytes =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter partialTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter partialBytes =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter clientSpansWithoutContext =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter singleSpanSampled =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter singleSpanUnsampled =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter capturedContinuations =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter cancelledContinuations =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter finishedContinuations =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter activatedScopes =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter closedScopes =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter scopeStackOverflow =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter scopeCloseErrors =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter userScopeCloseErrors =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter longRunningTracesWrite =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter longRunningTracesDropped =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter longRunningTracesExpired =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter clientStatsProcessedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter clientStatsProcessedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter clientStatsP0DroppedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter clientStatsP0DroppedTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter clientStatsRequests =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter clientStatsErrors =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter clientStatsDowngrades =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final StatsDClient statsd;
  private final long interval;
  private final TimeUnit units;

  @Override
  public void start() {
    if (started.compareAndSet(false, true)) {
      cancellation =
          AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
              new Flush(), this, interval, interval, units);
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
        userDropEnqueuedTraces.inc();
        break;
      case USER_KEEP:
        userKeepEnqueuedTraces.inc();
        break;
      case SAMPLER_DROP:
        samplerDropEnqueuedTraces.inc();
        break;
      case SAMPLER_KEEP:
        samplerKeepEnqueuedTraces.inc();
        break;
      default:
        unsetPriorityEnqueuedTraces.inc();
    }
    enqueuedSpans.inc(trace.size());
    checkForClientSpansWithoutContext(trace);
  }

  private void checkForClientSpansWithoutContext(final List<DDSpan> trace) {
    for (DDSpan span : trace) {
      if (span != null && span.getParentId() == ZERO) {
        String spanKind = span.getTag(SPAN_KIND, "undefined");
        if (SPAN_KIND_CLIENT.equals(spanKind)) {
          this.clientSpansWithoutContext.inc();
        }
      }
    }
  }

  @Override
  public void onFailedPublish(final int samplingPriority, final int spanCount) {
    switch (samplingPriority) {
      case USER_DROP:
        userDropDroppedSpans.inc(spanCount);
        userDropDroppedTraces.inc();
        break;
      case USER_KEEP:
        userKeepDroppedSpans.inc(spanCount);
        userKeepDroppedTraces.inc();
        break;
      case SAMPLER_DROP:
        samplerDropDroppedSpans.inc(spanCount);
        samplerDropDroppedTraces.inc();
        break;
      case SAMPLER_KEEP:
        samplerKeepDroppedSpans.inc(spanCount);
        samplerKeepDroppedTraces.inc();
        break;
      default:
        unsetPriorityDroppedSpans.inc(spanCount);
        unsetPriorityDroppedTraces.inc();
    }
  }

  @Override
  public void onPartialPublish(final int numberOfDroppedSpans) {
    partialTraces.inc();
    samplerDropDroppedSpans.inc(numberOfDroppedSpans);
  }

  @Override
  public void onScheduleFlush(final boolean previousIncomplete) {
    // not recorded
  }

  @Override
  public void onFlush(final boolean early) {}

  @Override
  public void onPartialFlush(final int sizeInBytes) {
    partialBytes.inc(sizeInBytes);
  }

  @Override
  public void onSingleSpanSample() {
    singleSpanSampled.inc();
  }

  @Override
  public void onSingleSpanUnsampled() {
    singleSpanUnsampled.inc();
  }

  @Override
  public void onSerialize(final int serializedSizeInBytes) {
    // DQH - Because of Java tracer's 2 phase acceptance and serialization scheme, this doesn't
    // map precisely
    enqueuedBytes.inc(serializedSizeInBytes);
  }

  @Override
  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {
    if (trace != null) {
      serialFailedDroppedTraces.inc();
      serialFailedDroppedSpans.inc(trace.size());
    }
  }

  @Override
  public void onCreateSpan() {
    createdSpans.inc();
  }

  @Override
  public void onFinishSpan() {
    finishedSpans.inc();
  }

  @Override
  public void onCreateTrace() {
    createdTraces.inc();
  }

  @Override
  public void onScopeCloseError(boolean manual) {
    scopeCloseErrors.inc();
    if (manual) {
      userScopeCloseErrors.inc();
    }
  }

  @Override
  public void onCaptureContinuation() {
    capturedContinuations.inc();
  }

  @Override
  public void onCancelContinuation() {
    cancelledContinuations.inc();
  }

  @Override
  public void onFinishContinuation() {
    finishedContinuations.inc();
  }

  @Override
  public void onActivateScope() {
    activatedScopes.inc();
  }

  @Override
  public void onCloseScope() {
    closedScopes.inc();
  }

  @Override
  public void onScopeStackOverflow() {
    scopeStackOverflow.inc();
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
    longRunningTracesWrite.inc(write);
    longRunningTracesDropped.inc(dropped);
    longRunningTracesExpired.inc(expired);
  }

  private void onSendAttempt(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    apiRequests.inc();
    flushedTraces.inc(traceCount);
    // TODO: missing queue.spans (# of spans being sent)
    flushedBytes.inc(sizeInBytes);

    if (response.exception() != null) {
      // covers communication errors -- both not receiving a response or
      // receiving malformed response (even when otherwise successful)
      apiErrors.inc();
    }

    Integer status = response.status();
    if (status != null) {
      if (200 == status) {
        apiResponsesOK.inc();
      } else {
        statsd.incrementCounter("api.responses.total", statusTagsCache.get(status));
      }
    }
  }

  @Override
  public void onClientStatTraceComputed(int countedSpans, int totalSpans, boolean dropped) {
    clientStatsProcessedTraces.inc();
    clientStatsProcessedSpans.inc(countedSpans);
    if (dropped) {
      clientStatsP0DroppedTraces.inc();
      clientStatsP0DroppedSpans.inc(totalSpans);
    }
  }

  @Override
  public void onClientStatPayloadSent() {
    clientStatsRequests.inc();
  }

  @Override
  public void onClientStatDowngraded() {
    clientStatsDowngrades.inc();
  }

  @Override
  public void onClientStatErrorReceived() {
    clientStatsErrors.inc();
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

    private final long[] previousCounts = new long[43];
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
            target.statsd, "stats.p0_dropped_traces", target.clientStatsP0DroppedTraces, NO_TAGS);
        reportIfChanged(
            target.statsd, "stats.p0_dropped_spans", target.clientStatsP0DroppedSpans, NO_TAGS);
        reportIfChanged(
            target.statsd, "stats.flushed_payloads", target.clientStatsRequests, NO_TAGS);
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
        StatsDClient statsDClient,
        String aspect,
        FixedSizeStripedLongCounter counter,
        String[] tags) {
      long count = counter.get();
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
        + apiRequests.get()
        + "\napiErrors="
        + apiErrors.get()
        + "\napiResponsesOK="
        + apiResponsesOK.get()
        + "\n"
        + "\nuserDropEnqueuedTraces="
        + userDropEnqueuedTraces.get()
        + "\nuserKeepEnqueuedTraces="
        + userKeepEnqueuedTraces.get()
        + "\nsamplerDropEnqueuedTraces="
        + samplerDropEnqueuedTraces.get()
        + "\nsamplerKeepEnqueuedTraces="
        + samplerKeepEnqueuedTraces.get()
        + "\nunsetPriorityEnqueuedTraces="
        + unsetPriorityEnqueuedTraces.get()
        + "\n"
        + "\nuserDropDroppedTraces="
        + userDropDroppedTraces.get()
        + "\nuserKeepDroppedTraces="
        + userKeepDroppedTraces.get()
        + "\nsamplerDropDroppedTraces="
        + samplerDropDroppedTraces.get()
        + "\nsamplerKeepDroppedTraces="
        + samplerKeepDroppedTraces.get()
        + "\nserialFailedDroppedTraces="
        + serialFailedDroppedTraces.get()
        + "\nunsetPriorityDroppedTraces="
        + unsetPriorityDroppedTraces.get()
        + "\n"
        + "\nuserDropDroppedSpans="
        + userDropDroppedSpans.get()
        + "\nuserKeepDroppedSpans="
        + userKeepDroppedSpans.get()
        + "\nsamplerDropDroppedSpans="
        + samplerDropDroppedSpans.get()
        + "\nsamplerKeepDroppedSpans="
        + samplerKeepDroppedSpans.get()
        + "\nserialFailedDroppedSpans="
        + serialFailedDroppedSpans.get()
        + "\nunsetPriorityDroppedSpans="
        + unsetPriorityDroppedSpans.get()
        + "\n"
        + "\nenqueuedSpans="
        + enqueuedSpans.get()
        + "\nenqueuedBytes="
        + enqueuedBytes.get()
        + "\ncreatedTraces="
        + createdTraces.get()
        + "\ncreatedSpans="
        + createdSpans.get()
        + "\nfinishedSpans="
        + finishedSpans.get()
        + "\nflushedTraces="
        + flushedTraces.get()
        + "\nflushedBytes="
        + flushedBytes.get()
        + "\npartialTraces="
        + partialTraces.get()
        + "\npartialBytes="
        + partialBytes.get()
        + "\n"
        + "\nclientSpansWithoutContext="
        + clientSpansWithoutContext.get()
        + "\n"
        + "\nsingleSpanSampled="
        + singleSpanSampled.get()
        + "\nsingleSpanUnsampled="
        + singleSpanUnsampled.get()
        + "\n"
        + "\ncapturedContinuations="
        + capturedContinuations.get()
        + "\ncancelledContinuations="
        + cancelledContinuations.get()
        + "\nfinishedContinuations="
        + finishedContinuations.get()
        + "\n"
        + "\nactivatedScopes="
        + activatedScopes.get()
        + "\nclosedScopes="
        + closedScopes.get()
        + "\nscopeStackOverflow="
        + scopeStackOverflow.get()
        + "\nscopeCloseErrors="
        + scopeCloseErrors.get()
        + "\nuserScopeCloseErrors="
        + userScopeCloseErrors.get()
        + "\n"
        + "\nlongRunningTracesWrite="
        + longRunningTracesWrite.get()
        + "\nlongRunningTracesDropped="
        + longRunningTracesDropped.get()
        + "\nlongRunningTracesExpired="
        + longRunningTracesExpired.get()
        + "\n"
        + "\nclientStatsRequests="
        + clientStatsRequests.get()
        + "\nclientStatsErrors="
        + clientStatsErrors.get()
        + "\nclientStatsDowngrades="
        + clientStatsDowngrades.get()
        + "\nclientStatsP0DroppedSpans="
        + clientStatsP0DroppedSpans.get()
        + "\nclientStatsP0DroppedTraces="
        + clientStatsP0DroppedTraces.get()
        + "\nclientStatsProcessedSpans="
        + clientStatsProcessedSpans.get()
        + "\nclientStatsProcessedTraces="
        + clientStatsProcessedTraces.get();
  }
}
