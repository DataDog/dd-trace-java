package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.metrics.SignalItem.ClearSignal;
import datadog.trace.common.metrics.SignalItem.StopSignal;
import datadog.trace.core.monitor.HealthMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Aggregator implements Runnable {

  private static final long DEFAULT_SLEEP_MILLIS = 10;

  private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

  private final MessagePassingQueue<InboxItem> inbox;
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
   * flush. Used by {@link ClientStatsAggregator} to reconcile its cached peer-tag schema against
   * {@link datadog.communication.ddagent.DDAgentFeaturesDiscovery}; running before the flush
   * guarantees that any test awaiting {@code writer.finishBucket()} observes the schema in its
   * post-reconcile state. May be {@code null}.
   */
  private final Runnable onReportCycle;

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "the field is confined to the agent thread running the Aggregator")
  private boolean dirty;

  Aggregator(
      MetricWriter writer,
      MessagePassingQueue<InboxItem> inbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      HealthMetrics healthMetrics,
      AdditionalTagsSchema additionalTagsSchema,
      Runnable onReportCycle) {
    this(
        writer,
        inbox,
        maxAggregates,
        reportingInterval,
        reportingIntervalTimeUnit,
        DEFAULT_SLEEP_MILLIS,
        healthMetrics,
        additionalTagsSchema,
        onReportCycle);
  }

  Aggregator(
      MetricWriter writer,
      MessagePassingQueue<InboxItem> inbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      long sleepMillis,
      HealthMetrics healthMetrics,
      AdditionalTagsSchema additionalTagsSchema,
      Runnable onReportCycle) {
    this.writer = writer;
    this.inbox = inbox;
    this.aggregates = new AggregateTable(maxAggregates, additionalTagsSchema);
    this.reportingIntervalNanos = reportingIntervalTimeUnit.toNanos(reportingInterval);
    this.sleepMillis = sleepMillis;
    this.healthMetrics = healthMetrics;
    this.onReportCycle = onReportCycle;
  }

  void resetPropertyHandlers(HealthMetrics healthMetrics) {
    aggregates.resetHandlers(healthMetrics);
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    Drainer drainer = new Drainer();
    while (!currentThread.isInterrupted() && !drainer.stopped) {
      try {
        if (!inbox.isEmpty()) {
          inbox.drain(drainer);
        } else {
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

  private final class Drainer implements MessagePassingQueue.Consumer<InboxItem> {

    boolean stopped = false;

    @Override
    public void accept(InboxItem item) {
      if (item == ClearSignal.CLEAR) {
        // ClearSignal is routed through the inbox (rather than letting the caller mutate
        // AggregateTable directly) so the aggregator thread stays the sole writer. AggregateTable
        // is not thread-safe; a direct clear() from e.g. the OkHttpSink callback thread would
        // race with Drainer.accept on this thread.
        //
        // We deliberately do NOT call inbox.clear() here. Doing so would erase any queued STOP
        // (or REPORT) signals that happen to sit behind CLEAR -- a real concern when a
        // downgrade is followed quickly by close(), where the trampled STOP leaves the
        // aggregator thread spinning until thread.join times out. features.supportsMetrics() is
        // already false by the time CLEAR was offered, so producers have stopped publishing;
        // any in-flight snapshots will drain naturally into the just-cleared table, get
        // re-aggregated, and flushed on the next report -- where the agent rejects them again,
        // triggering another DOWNGRADED -> disable() -> CLEAR cycle. Worst case: one extra
        // reporting cycle of wasted work, which we accept for the safety of preserving STOP.
        if (!stopped) {
          aggregates.clear();
          // Clear dirty too -- without this, the next report() would see dirty=true, run
          // expungeStaleAggregates against the (now-empty) table, find isEmpty()=true, and skip
          // the flush anyway. Same observable outcome, but resetting here keeps the invariant
          // "dirty implies there's data to flush" honest.
          dirty = false;
        }
        ((SignalItem) item).complete();
      } else if (item instanceof SignalItem) {
        SignalItem signal = (SignalItem) item;
        if (!stopped) {
          report(wallClockTime(), signal);
          stopped = item instanceof StopSignal;
          if (stopped) {
            signal.complete();
          }
        } else {
          signal.ignore();
        }
      } else if (item instanceof SpanSnapshot && !stopped) {
        SpanSnapshot snapshot = (SpanSnapshot) item;
        AggregateEntry entry = aggregates.findOrInsert(snapshot);
        if (entry != null) {
          entry.recordOneDuration(snapshot.tagAndDuration);
          dirty = true;
        } else {
          // table at cap with no stale entry available to evict
          healthMetrics.onStatsAggregateDropped();
        }
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
                entry.clearAggregate();
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
