package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.metrics.SignalItem.ClearSignal;
import datadog.trace.common.metrics.SignalItem.StopSignal;
import datadog.trace.core.monitor.HealthMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Aggregator implements Runnable {

  private static final long DEFAULT_SLEEP_MILLIS = 10;

  /** Non-capturing -- the writer arrives via the forEach context arg. */
  private static final BiConsumer<MetricWriter, AggregateEntry> WRITE_AND_CLEAR =
      (writer, entry) -> {
        writer.add(entry);
        entry.clear();
      };

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
      HealthMetrics healthMetrics) {
    this(
        writer,
        inbox,
        maxAggregates,
        reportingInterval,
        reportingIntervalTimeUnit,
        DEFAULT_SLEEP_MILLIS,
        healthMetrics);
  }

  Aggregator(
      MetricWriter writer,
      MessagePassingQueue<InboxItem> inbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      long sleepMillis,
      HealthMetrics healthMetrics) {
    this.writer = writer;
    this.inbox = inbox;
    this.aggregates = new AggregateTable(maxAggregates);
    this.reportingIntervalNanos = reportingIntervalTimeUnit.toNanos(reportingInterval);
    this.sleepMillis = sleepMillis;
    this.healthMetrics = healthMetrics;
  }

  public void clearAggregates() {
    this.aggregates.clear();
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
        if (!stopped) {
          aggregates.clear();
          inbox.clear();
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
    boolean skipped = true;
    if (dirty) {
      try {
        aggregates.expungeStaleAggregates();
        if (!aggregates.isEmpty()) {
          skipped = false;
          writer.startBucket(aggregates.size(), when, reportingIntervalNanos);
          aggregates.forEach(writer, WRITE_AND_CLEAR);
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
