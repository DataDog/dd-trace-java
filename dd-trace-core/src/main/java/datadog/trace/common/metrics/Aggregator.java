package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.metrics.SignalItem.ClearSignal;
import datadog.trace.common.metrics.SignalItem.StopSignal;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.concurrent.MpscRingBuffer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Aggregator implements Runnable {

  private static final long DEFAULT_SLEEP_MILLIS = 10;

  private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

  private final MpscRingBuffer<SpanSnapshot> dataInbox;
  private final MessagePassingQueue<SignalItem> signalInbox;
  private final AggregateTable aggregates;
  private final MetricWriter writer;
  private final HealthMetrics healthMetrics;
  // the reporting interval controls how much history will be buffered
  // when the agent is unresponsive (only 10 pending requests will be
  // buffered by OkHttpSink)
  private final long reportingIntervalNanos;

  private final long sleepMillis;

  /**
   * Per-cycle hook run on the aggregator thread at the start of each report cycle, before the
   * flush. Used by {@link ConflatingMetricsAggregator} to reconcile its cached peer-tag schema
   * against {@link datadog.communication.ddagent.DDAgentFeaturesDiscovery}; running before the
   * flush guarantees that any test awaiting {@code writer.finishBucket()} observes the schema in
   * its post-reconcile state. May be {@code null}.
   */
  private final Runnable onReportCycle;

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "the field is confined to the agent thread running the Aggregator")
  private boolean dirty;

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "the field is confined to the agent thread running the Aggregator")
  private boolean stopped;

  /**
   * Static-singleton snapshot handler. Reads from the slot via {@code this} (passed as the context)
   * and avoids per-drain lambda capture.
   */
  private static final BiConsumer<Aggregator, SpanSnapshot> SNAPSHOT_HANDLER =
      Aggregator::handleSnapshot;

  Aggregator(
      MetricWriter writer,
      MpscRingBuffer<SpanSnapshot> dataInbox,
      MessagePassingQueue<SignalItem> signalInbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      HealthMetrics healthMetrics,
      Runnable onReportCycle) {
    this(
        writer,
        dataInbox,
        signalInbox,
        maxAggregates,
        reportingInterval,
        reportingIntervalTimeUnit,
        DEFAULT_SLEEP_MILLIS,
        healthMetrics,
        onReportCycle);
  }

  Aggregator(
      MetricWriter writer,
      MpscRingBuffer<SpanSnapshot> dataInbox,
      MessagePassingQueue<SignalItem> signalInbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      long sleepMillis,
      HealthMetrics healthMetrics,
      Runnable onReportCycle) {
    this.writer = writer;
    this.dataInbox = dataInbox;
    this.signalInbox = signalInbox;
    this.aggregates = new AggregateTable(maxAggregates);
    this.reportingIntervalNanos = reportingIntervalTimeUnit.toNanos(reportingInterval);
    this.sleepMillis = sleepMillis;
    this.healthMetrics = healthMetrics;
    this.onReportCycle = onReportCycle;
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    while (!currentThread.isInterrupted() && !stopped) {
      try {
        int drainedData = dataInbox.drain(this, SNAPSHOT_HANDLER);
        // Signals are processed after the data drain. REPORT/STOP additionally drain any
        // snapshots that arrived between the drain above and the signal's enqueue -- the
        // signal-channel offer happens-before the poll here, so any publish that completed
        // before report()/stop() is guaranteed visible to the inline drain in handleSignal.
        SignalItem signal;
        while (!stopped && (signal = signalInbox.poll()) != null) {
          handleSignal(signal);
        }
        if (stopped) break;
        if (drainedData == 0 && signalInbox.isEmpty()) {
          Thread.sleep(sleepMillis);
        }
      } catch (InterruptedException e) {
        currentThread.interrupt();
      } catch (Throwable error) {
        log.debug("error aggregating metrics", error);
      }
    }
    log.debug("metrics aggregator exited");
  }

  private static void handleSnapshot(Aggregator agg, SpanSnapshot snapshot) {
    // Skip-sentinel from the producer's overclaim path: the producer claimed this slot
    // speculatively (one slot per span in the trace) but the span turned out to be non-eligible
    // or the trace hit an ignored-resource. shouldComputeMetric guarantees duration > 0 for any
    // real publish, so tagAndDuration == 0 uniquely identifies a skip.
    if (snapshot.tagAndDuration == 0L) {
      return;
    }
    AggregateEntry entry = agg.aggregates.findOrInsert(snapshot);
    if (entry != null) {
      entry.recordOneDuration(snapshot.tagAndDuration);
      agg.dirty = true;
    } else {
      // table at cap with no stale entry available to evict
      agg.healthMetrics.onStatsAggregateDropped();
    }
  }

  private void handleSignal(SignalItem signal) {
    if (signal == ClearSignal.CLEAR) {
      // ClearSignal is routed through the signal channel (rather than letting the caller mutate
      // AggregateTable directly) so the aggregator thread stays the sole writer. AggregateTable
      // is not thread-safe; a direct clear() from e.g. the OkHttpSink callback thread would race
      // with the data-drain on this thread.
      //
      // We do NOT clear the snapshot data ring here. Any in-flight snapshots will drain
      // naturally into the just-cleared table, get re-aggregated, and flushed on the next
      // report -- where the agent rejects them again, triggering another DOWNGRADED -> disable()
      // -> CLEAR cycle. Worst case: one extra reporting cycle of wasted work.
      aggregates.clear();
      // Clear dirty too -- without this, the next report() would see dirty=true, run
      // expungeStaleAggregates against the (now-empty) table, find isEmpty()=true, and skip
      // the flush anyway. Same observable outcome, but resetting here keeps the invariant
      // "dirty implies there's data to flush" honest.
      dirty = false;
      signal.complete();
    } else {
      // STOP or REPORT: catch up on any data that arrived between the loop's main drain and the
      // signal being enqueued. Producer's signalInbox.offer happens-after its dataInbox publish,
      // so by the time we observe the signal, every snapshot the producer had published is
      // visible to drain() here. This is what gives report() bucket-boundary determinism.
      dataInbox.drain(this, SNAPSHOT_HANDLER);
      report(wallClockTime(), signal);
      if (signal instanceof StopSignal) {
        stopped = true;
        signal.complete();
      }
    }
  }

  private void report(long when, SignalItem signal) {
    // Per-cycle hook on the aggregator thread -- used by ClientStatsAggregator to reconcile the
    // cached peer-tag schema against feature discovery. Runs before the flush so any test that
    // awaits writer.finishBucket() observes the schema in its post-reconcile state, and so
    // subsequent producer publishes (which may happen as soon as the flush completes) see the new
    // schema without an additional handoff.
    if (onReportCycle != null) {
      onReportCycle.run();
    }
    boolean skipped = true;
    if (dirty) {
      try {
        aggregates.expungeStaleAggregates();
        if (!aggregates.isEmpty()) {
          skipped = false;
          writer.startBucket(aggregates.size(), when, reportingIntervalNanos);
          aggregates.forEach(
              writer,
              (w, entry) -> {
                w.add(entry);
                entry.clear();
              });
          // note that this may do IO and block
          writer.finishBucket();
        }
      } catch (Throwable error) {
        writer.reset();
        log.debug("Error publishing metrics. Dropping payload", error);
      }
      dirty = false;
    }
    signal.complete();
    if (skipped) {
      log.debug("skipped metrics reporting because no points have changed");
    }
  }

  private long wallClockTime() {
    return MILLISECONDS.toNanos(System.currentTimeMillis());
  }
}
