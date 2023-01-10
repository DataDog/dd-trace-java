package datadog.trace.core.monitor;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
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

public class TracerHealthMetrics extends HealthMetrics implements AutoCloseable {

  private static final IntFunction<String[]> STATUS_TAGS =
      httpStatus -> new String[] {"status:" + httpStatus};

  private static final String[] NO_TAGS = new String[0];
  private final RadixTreeCache<String[]> statusTagsCache =
      new RadixTreeCache<>(16, 32, STATUS_TAGS, 200, 400);

  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile AgentTaskScheduler.Scheduled<TracerHealthMetrics> cancellation;

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

  private final FixedSizeStripedLongCounter enqueuedSpans =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter createdTraces =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final FixedSizeStripedLongCounter createdSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter sampledSpans =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter manualTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter cancelledContinuations =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter finishedContinuations =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter partialTraces =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter droppedSpans =
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
  }

  @Override
  public void onFailedPublish(final int samplingPriority) {
    switch (samplingPriority) {
      case USER_DROP:
        userDropDroppedTraces.inc();
        break;
      case USER_KEEP:
        userKeepDroppedTraces.inc();
        break;
      case SAMPLER_DROP:
        samplerDropDroppedTraces.inc();
        break;
      case SAMPLER_KEEP:
        samplerKeepDroppedTraces.inc();
        break;
      default:
        unsetPriorityDroppedTraces.inc();
    }
  }

  @Override
  public void onPartialPublish(final int numberOfDroppedSpans) {
    partialTraces.inc();
    droppedSpans.inc(numberOfDroppedSpans);
  }

  @Override
  public void onScheduleFlush(final boolean previousIncomplete) {
    // not recorded
  }

  @Override
  public void onFlush(final boolean early) {}

  @Override
  public void onPartialFlush(final int sizeInBytes) {
    statsd.count("span.flushed.partial", sizeInBytes, NO_TAGS);
  }

  @Override
  public void onSerialize(final int serializedSizeInBytes) {
    // DQH - Because of Java tracer's 2 phase acceptance and serialization scheme, this doesn't
    // map precisely
    statsd.count("queue.enqueued.bytes", serializedSizeInBytes, NO_TAGS);
  }

  @Override
  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {
    serialFailedDroppedTraces.inc();
  }

  @Override
  public void onCreateSpan() {
    createdSpans.inc();
  }

  @Override
  public void onCreateTrace() {
    createdTraces.inc();
  }

  @Override
  public void onCreateManualTrace() {
    manualTraces.inc();
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
  public void onSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    onSendAttempt(traceCount, sizeInBytes, response);
  }

  @Override
  public void onFailedSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    onSendAttempt(traceCount, sizeInBytes, response);
  }

  private void onSendAttempt(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    statsd.incrementCounter("api.requests.total", NO_TAGS);
    statsd.count("flush.traces.total", traceCount, NO_TAGS);
    // TODO: missing queue.spans (# of spans being sent)
    statsd.count("flush.bytes.total", sizeInBytes, NO_TAGS);

    if (response.exception() != null) {
      // covers communication errors -- both not receiving a response or
      // receiving malformed response (even when otherwise successful)
      statsd.incrementCounter("api.errors.total", NO_TAGS);
    }

    if (response.status() != null) {
      statsd.incrementCounter("api.responses.total", statusTagsCache.get(response.status()));
    }
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

    @Override
    public void run(TracerHealthMetrics target) {
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
          target.statsd, "queue.dropped.traces", target.samplerDropDroppedTraces, SAMPLER_DROP_TAG);
      reportIfChanged(
          target.statsd, "queue.dropped.traces", target.samplerKeepDroppedTraces, SAMPLER_KEEP_TAG);
      reportIfChanged(
          target.statsd,
          "queue.dropped.traces",
          target.serialFailedDroppedTraces,
          SERIAL_FAILED_TAG);
      reportIfChanged(
          target.statsd, "queue.dropped.traces", target.unsetPriorityDroppedTraces, UNSET_TAG);
      reportIfChanged(target.statsd, "queue.enqueued.spans", target.enqueuedSpans, NO_TAGS);
      reportIfChanged(target.statsd, "trace.pending.created", target.createdTraces, NO_TAGS);
      reportIfChanged(target.statsd, "span.pending.created", target.createdSpans, NO_TAGS);
      reportIfChanged(
          target.statsd, "span.continuations.canceled", target.cancelledContinuations, NO_TAGS);
      reportIfChanged(
          target.statsd, "span.continuations.finished", target.finishedContinuations, NO_TAGS);
      reportIfChanged(target.statsd, "queue.partial.traces", target.partialTraces, NO_TAGS);
      reportIfChanged(target.statsd, "queue.dropped.spans", target.droppedSpans, NO_TAGS);
    }

    private void reportIfChanged(
        StatsDClient statsDClient,
        String aspect,
        FixedSizeStripedLongCounter counter,
        String[] tags) {
      long count = counter.getAndReset();
      if (count > 0) {
        statsDClient.count(aspect, count, tags);
      }
    }
  }
}
