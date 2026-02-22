package com.datadog.debugger.sink;

import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.DebuggerMetrics;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext.SkipCause;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.util.AgentTaskScheduler;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects data that needs to be sent to the backend: Snapshots, metrics and statuses */
public class DebuggerSink {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerSink.class);
  private static final double FREE_CAPACITY_LOWER_THRESHOLD = 0.25;
  private static final double FREE_CAPACITY_UPPER_THRESHOLD = 0.75;
  private static final int LOW_RATE_MIN_FLUSH_INTERVAL = 100;
  private static final int LOW_RATE_MAX_FLUSH_INTERVAL = 2000;
  private static final long LOW_RATE_INITIAL_FLUSH_INTERVAL = 1000;
  static final long LOW_RATE_STEP_SIZE = 200;
  private static final String PREFIX = "debugger.sink.";
  private static final String DROPPED_REQ_METRIC = PREFIX + "dropped.requests";
  private static final String UPLOAD_REMAINING_CAP_METRIC =
      PREFIX + "upload.queue.remaining.capacity";
  private static final String CURRENT_FLUSH_INTERVAL_METRIC = PREFIX + "current.flush.interval";
  private static final String SKIP_METRIC = PREFIX + "skip";

  private final ProbeStatusSink probeStatusSink;
  private final SnapshotSink snapshotSink;
  private final SymbolSink symbolSink;
  private final DebuggerMetrics debuggerMetrics;
  private final String tags;
  private final AtomicLong highRateDropped = new AtomicLong();
  private final int uploadFlushInterval;
  private final AgentTaskScheduler lowRateScheduler = AgentTaskScheduler.get();
  private volatile AgentTaskScheduler.Scheduled<DebuggerSink> lowRateScheduled;
  private volatile AgentTaskScheduler.Scheduled<DebuggerSink> flushIntervalScheduled;
  private volatile long currentLowRateFlushInterval = LOW_RATE_INITIAL_FLUSH_INTERVAL;

  public DebuggerSink(Config config, ProbeStatusSink probeStatusSink) {
    this(
        config,
        null,
        DebuggerMetrics.getInstance(config),
        probeStatusSink,
        new SnapshotSink(
            config,
            null,
            new BatchUploader(
                "Snapshots",
                config,
                config.getFinalDebuggerSnapshotUrl(),
                SnapshotSink.RETRY_POLICY),
            new BatchUploader(
                "Logs", config, config.getFinalDebuggerSnapshotUrl(), SnapshotSink.RETRY_POLICY)),
        new SymbolSink(config));
  }

  public DebuggerSink(
      Config config,
      String tags,
      DebuggerMetrics debuggerMetrics,
      ProbeStatusSink probeStatusSink,
      SnapshotSink snapshotSink,
      SymbolSink symbolSink) {
    this.tags = tags;
    this.debuggerMetrics = debuggerMetrics;
    this.probeStatusSink = probeStatusSink;
    this.snapshotSink = snapshotSink;
    this.symbolSink = symbolSink;
    this.uploadFlushInterval = config.getDynamicInstrumentationUploadFlushInterval();
  }

  public void start() {
    if (uploadFlushInterval == 0) {
      flushIntervalScheduled =
          lowRateScheduler.scheduleAtFixedRate(
              this::reconsiderLowRateFlushInterval, this, 0, 200, TimeUnit.MILLISECONDS);
    } else {
      currentLowRateFlushInterval = uploadFlushInterval;
    }
    LOGGER.debug("Scheduling low rate debugger sink flush to {}ms", currentLowRateFlushInterval);
    lowRateScheduled =
        lowRateScheduler.scheduleAtFixedRate(
            this::lowRateFlush, this, 0, currentLowRateFlushInterval, TimeUnit.MILLISECONDS);
    snapshotSink.start();
  }

  public void stop() {
    lowRateFlush(this);
    snapshotSink.highRateFlush(null);
    cancelSchedule(this.flushIntervalScheduled);
    cancelSchedule(this.lowRateScheduled);
    probeStatusSink.stop();
    symbolSink.stop();
    snapshotSink.stop();
  }

  private void cancelSchedule(AgentTaskScheduler.Scheduled<DebuggerSink> scheduled) {
    if (scheduled != null) {
      scheduled.cancel();
    }
  }

  public SnapshotSink getSnapshotSink() {
    return snapshotSink;
  }

  public ProbeStatusSink getProbeStatusSink() {
    return probeStatusSink;
  }

  public SymbolSink getSymbolSink() {
    return symbolSink;
  }

  public void addSnapshot(Snapshot snapshot) {
    boolean added = snapshotSink.addLowRate(snapshot);
    if (!added) {
      debuggerMetrics.count(DROPPED_REQ_METRIC, 1);
    } else {
      if (!(snapshot.getProbe() instanceof ExceptionProbe)) {
        // do not report emitting for exception probes
        probeStatusSink.addEmitting(snapshot.getProbe().getProbeId());
      }
    }
  }

  public void addHighRateSnapshot(Snapshot snapshot) {
    boolean added = snapshotSink.addHighRate(snapshot);
    if (!added) {
      long dropped = highRateDropped.incrementAndGet();
      if (dropped % 100 == 0) {
        debuggerMetrics.count(DROPPED_REQ_METRIC, 100);
      }
    } else {
      probeStatusSink.addEmitting(snapshot.getProbe().getProbeId());
    }
  }

  ProbeStatusSink getProbeDiagnosticsSink() {
    return probeStatusSink;
  }

  private void lowRateReschedule() {
    cancelSchedule(this.lowRateScheduled);
    LOGGER.debug("Rescheduling low rate debugger sink flush to {}ms", currentLowRateFlushInterval);
    this.lowRateScheduled =
        lowRateScheduler.scheduleAtFixedRate(
            this::lowRateFlush,
            this,
            currentLowRateFlushInterval,
            currentLowRateFlushInterval,
            TimeUnit.MILLISECONDS);
  }

  // visible for testing
  void lowRateFlush(DebuggerSink ignored) {
    symbolSink.flush();
    probeStatusSink.flush(tags);
    snapshotSink.lowRateFlush(tags);
  }

  private void reconsiderLowRateFlushInterval(DebuggerSink debuggerSink) {
    debuggerMetrics.histogram(UPLOAD_REMAINING_CAP_METRIC, snapshotSink.remainingCapacity());
    debuggerMetrics.histogram(CURRENT_FLUSH_INTERVAL_METRIC, currentLowRateFlushInterval);
    doReconsiderLowRateFlushInterval();
  }

  // Depending on the remaining capacity in the upload queue, we adjust the flush interval
  // to avoid filling the queue if we are waiting too long between flushes.
  // We are using 2 thresholds to adjust the flush interval:
  // - if the remaining capacity is below the lower threshold, we decrease the flush interval
  // - if the remaining capacity is above the upper threshold, we increase the flush interval
  void doReconsiderLowRateFlushInterval() {
    double remainingCapacityPercent =
        snapshotSink.remainingCapacity() * 1D / SnapshotSink.LOW_RATE_CAPACITY;
    long currentInterval = currentLowRateFlushInterval;
    long newInterval = currentInterval;
    if (remainingCapacityPercent <= FREE_CAPACITY_LOWER_THRESHOLD) {
      newInterval = Math.max(currentInterval - LOW_RATE_STEP_SIZE, LOW_RATE_MIN_FLUSH_INTERVAL);
    } else if (remainingCapacityPercent >= FREE_CAPACITY_UPPER_THRESHOLD) {
      newInterval = Math.min(currentInterval + LOW_RATE_STEP_SIZE, LOW_RATE_MAX_FLUSH_INTERVAL);
    }
    if (newInterval != currentInterval) {
      currentLowRateFlushInterval = newInterval;
      LOGGER.debug(
          "Changing flush interval. Remaining available capacity in upload queue {}%, new flush interval {}ms",
          remainingCapacityPercent * 100, newInterval);
      lowRateReschedule();
    }
  }

  public void addReceived(ProbeId probeId) {
    probeStatusSink.addReceived(probeId);
  }

  public void addInstalled(ProbeId probeId) {
    probeStatusSink.addInstalled(probeId);
  }

  public void addBlocked(ProbeId probeId) {
    probeStatusSink.addBlocked(probeId);
  }

  public void addError(ProbeId probeId, String msg) {
    probeStatusSink.addError(probeId, msg);
  }

  public void removeDiagnostics(ProbeId probeId) {
    probeStatusSink.removeDiagnostics(probeId);
  }

  public void addDiagnostics(ProbeId probeId, List<DiagnosticMessage> messages) {
    for (DiagnosticMessage msg : messages) {
      switch (msg.getKind()) {
        case INFO:
          LOGGER.info(msg.getMessage());
          break;
        case WARN:
          LOGGER.warn(msg.getMessage());
          break;
        case ERROR:
          LOGGER.error(msg.getMessage());
          reportError(probeId, msg);
          break;
      }
    }
  }

  private void reportError(ProbeId probeId, DiagnosticMessage msg) {
    Throwable throwable = msg.getThrowable();
    if (throwable != null) {
      probeStatusSink.addError(probeId, throwable);
    } else {
      probeStatusSink.addError(probeId, msg.getMessage());
    }
  }

  /** Notifies the snapshot was skipped for one of the SkipCause reason */
  public void skipSnapshot(String probeId, SkipCause cause) {
    debuggerMetrics.incrementCounter(SKIP_METRIC, cause.tag(), "probe_id:" + probeId);
  }

  long getCurrentLowRateFlushInterval() {
    return currentLowRateFlushInterval;
  }
}
