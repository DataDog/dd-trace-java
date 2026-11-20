package com.datadog.debugger.sink;

import static datadog.trace.api.debugger.DebuggerMetricCollector.DroppedReason.QUEUE_FULL;

import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.uploader.BatchUploader;
import datadog.trace.api.Config;
import datadog.trace.api.debugger.DebuggerMetricCollector;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.util.AgentTaskScheduler;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

  private final ProbeStatusSink probeStatusSink;
  private final SnapshotSink snapshotSink;
  private final SymbolSink symbolSink;
  private final DebuggerMetricCollector metricCollector;
  private final String tags;
  private final int uploadFlushInterval;
  private final AgentTaskScheduler lowRateScheduler = AgentTaskScheduler.get();
  private volatile AgentTaskScheduler.Scheduled<DebuggerSink> lowRateScheduled;
  private volatile AgentTaskScheduler.Scheduled<DebuggerSink> flushIntervalScheduled;
  private volatile long currentLowRateFlushInterval = LOW_RATE_INITIAL_FLUSH_INTERVAL;

  public DebuggerSink(Config config, ProbeStatusSink probeStatusSink) {
    this(
        config,
        null,
        DebuggerMetricCollector.get(),
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
      DebuggerMetricCollector metricCollector,
      ProbeStatusSink probeStatusSink,
      SnapshotSink snapshotSink,
      SymbolSink symbolSink) {
    this.tags = tags;
    this.metricCollector = metricCollector;
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
    cancelSchedule(this.flushIntervalScheduled);
    cancelSchedule(this.lowRateScheduled);
    lowRateFlush(this);
    snapshotSink.highRateFlush(null);
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
      metricCollector.recordEventDropped(QUEUE_FULL);
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
      metricCollector.recordEventDropped(QUEUE_FULL);
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

  @VisibleForTesting
  void lowRateFlush(DebuggerSink ignored) {
    symbolSink.flush();
    probeStatusSink.flush(tags);
    snapshotSink.lowRateFlush(tags);
  }

  private void reconsiderLowRateFlushInterval(DebuggerSink debuggerSink) {
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
        case WARN:
          LOGGER.debug(msg.getMessage());
          break;
        case ERROR:
          LOGGER.debug(msg.getMessage());
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
  public void skipSnapshot(String probeId, DebuggerMetricCollector.SkippedReason reason) {
    metricCollector.recordEventSkipped(reason);
  }

  long getCurrentLowRateFlushInterval() {
    return currentLowRateFlushInterval;
  }
}
