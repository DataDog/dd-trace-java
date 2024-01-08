package com.datadog.debugger.sink;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.DebuggerMetrics;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.TagsHelper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects data that needs to be sent to the backend: Snapshots, metrics and statuses */
public class DebuggerSink {
  private static final Logger log = LoggerFactory.getLogger(DebuggerSink.class);
  private static final double FREE_CAPACITY_LOWER_THRESHOLD = 0.25;
  private static final double FREE_CAPACITY_UPPER_THRESHOLD = 0.75;
  private static final int MIN_FLUSH_INTERVAL = 100;
  private static final int MAX_FLUSH_INTERVAL = 2000;
  private static final long INITIAL_FLUSH_INTERVAL = 1000;
  private static final String PREFIX = "debugger.sink.";
  private static final int CAPACITY = 1000;
  static final long STEP_SIZE = 200;

  private final ProbeStatusSink probeStatusSink;
  private final SnapshotSink snapshotSink;
  private final SymbolSink symbolSink;
  private final DebuggerMetrics debuggerMetrics;
  private final BatchUploader snapshotUploader;
  private final String tags;
  private final int uploadFlushInterval;

  private volatile AgentTaskScheduler.Scheduled<DebuggerSink> scheduled;
  private volatile AgentTaskScheduler.Scheduled<DebuggerSink> flushIntervalScheduled;
  private volatile long currentFlushInterval = INITIAL_FLUSH_INTERVAL;

  public DebuggerSink(Config config, ProbeStatusSink probeStatusSink) {
    this(
        config,
        new BatchUploader(config, config.getFinalDebuggerSnapshotUrl()),
        DebuggerMetrics.getInstance(config),
        probeStatusSink,
        new SnapshotSink(config),
        new SymbolSink(config));
  }

  // Used only for tests
  DebuggerSink(Config config, ProbeStatusSink probeStatusSink, BatchUploader snapshotUploader) {
    this(
        config,
        snapshotUploader,
        DebuggerMetrics.getInstance(config),
        probeStatusSink,
        new SnapshotSink(config),
        new SymbolSink(config));
  }

  DebuggerSink(Config config, BatchUploader snapshotUploader, DebuggerMetrics debuggerMetrics) {
    this(
        config,
        snapshotUploader,
        debuggerMetrics,
        new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false),
        new SnapshotSink(config),
        new SymbolSink(config));
  }

  public DebuggerSink(
      Config config,
      BatchUploader snapshotUploader,
      DebuggerMetrics debuggerMetrics,
      ProbeStatusSink probeStatusSink,
      SnapshotSink snapshotSink,
      SymbolSink symbolSink) {
    this.snapshotUploader = snapshotUploader;
    tags = getDefaultTagsMergedWithGlobalTags(config);
    this.debuggerMetrics = debuggerMetrics;
    this.probeStatusSink = probeStatusSink;
    this.snapshotSink = snapshotSink;
    this.symbolSink = symbolSink;
    this.uploadFlushInterval = config.getDebuggerUploadFlushInterval();
  }

  private static String getDefaultTagsMergedWithGlobalTags(Config config) {
    String debuggerTags =
        TagsHelper.concatTags(
            "env:" + config.getEnv(),
            "version:" + config.getVersion(),
            "debugger_version:" + DDTraceCoreInfo.VERSION,
            "agent_version:" + DebuggerAgent.getAgentVersion(),
            "host_name:" + config.getHostName());
    if (config.getGlobalTags().isEmpty()) {
      return debuggerTags;
    }
    String globalTags =
        config.getGlobalTags().entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .collect(Collectors.joining(","));
    return debuggerTags + "," + globalTags;
  }

  public void start() {
    if (uploadFlushInterval == 0) {
      flushIntervalScheduled =
          AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
              this::reconsiderFlushInterval, this, 0, 200, TimeUnit.MILLISECONDS);
    } else {
      currentFlushInterval = uploadFlushInterval;
    }
    scheduled =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            this::flush, this, 0, currentFlushInterval, TimeUnit.MILLISECONDS);
  }

  public void stop() {
    AgentTaskScheduler.Scheduled<DebuggerSink> localScheduled = this.scheduled;
    if (localScheduled != null) {
      localScheduled.cancel();
    }
    AgentTaskScheduler.Scheduled<DebuggerSink> localFlushIntervalScheduled =
        this.flushIntervalScheduled;
    if (localFlushIntervalScheduled != null) {
      localFlushIntervalScheduled.cancel();
    }
  }

  public BatchUploader getSnapshotUploader() {
    return snapshotUploader;
  }

  public ProbeStatusSink getProbeStatusSink() {
    return probeStatusSink;
  }

  public SymbolSink getSymbolSink() {
    return symbolSink;
  }

  public void addSnapshot(Snapshot snapshot) {
    boolean added = snapshotSink.offer(snapshot);
    if (!added) {
      debuggerMetrics.count(PREFIX + "dropped.requests", 1);
    } else {
      probeStatusSink.addEmitting(snapshot.getProbe().getProbeId());
    }
  }

  ProbeStatusSink getProbeDiagnosticsSink() {
    return probeStatusSink;
  }

  private void reschedule() {
    AgentTaskScheduler.Scheduled<DebuggerSink> localScheduled = this.scheduled;
    if (localScheduled != null) {
      localScheduled.cancel();
    }
    this.scheduled =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            this::flush, this, currentFlushInterval, currentFlushInterval, TimeUnit.MILLISECONDS);
  }

  // visible for testing
  void flush(DebuggerSink ignored) {
    symbolSink.flush();
    probeStatusSink.flush(tags);
    List<String> snapshots = snapshotSink.getSerializedSnapshots();
    if (snapshots.isEmpty()) {
      return;
    }
    if (snapshots.size() > 0) {
      uploadPayloads(snapshots);
    }
  }

  private void uploadPayloads(List<String> payloads) {
    List<byte[]> batches = IntakeBatchHelper.createBatches(payloads);
    for (byte[] batch : batches) {
      snapshotUploader.upload(batch, tags);
    }
  }

  private void reconsiderFlushInterval(DebuggerSink debuggerSink) {
    debuggerMetrics.histogram(
        PREFIX + "upload.queue.remaining.capacity", snapshotSink.remainingCapacity());
    debuggerMetrics.histogram(PREFIX + "current.flush.interval", currentFlushInterval);
    doReconsiderFlushInterval();
  }

  void doReconsiderFlushInterval() {
    double remainingCapacityPercent = snapshotSink.remainingCapacity() * 1D / CAPACITY;
    long currentInterval = currentFlushInterval;
    long newInterval = currentInterval;
    if (remainingCapacityPercent <= FREE_CAPACITY_LOWER_THRESHOLD) {
      newInterval = Math.max(currentInterval - STEP_SIZE, MIN_FLUSH_INTERVAL);
    } else if (remainingCapacityPercent >= FREE_CAPACITY_UPPER_THRESHOLD) {
      newInterval = Math.min(currentInterval + STEP_SIZE, MAX_FLUSH_INTERVAL);
    }
    if (newInterval != currentInterval) {
      currentFlushInterval = newInterval;
      log.debug(
          "Changing flush interval. Remaining available capacity in upload queue {}%, new flush interval {}ms",
          remainingCapacityPercent * 100, currentInterval);
      reschedule();
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

  public void removeDiagnostics(ProbeId probeId) {
    probeStatusSink.removeDiagnostics(probeId);
  }

  public void addDiagnostics(ProbeId probeId, List<DiagnosticMessage> messages) {
    for (DiagnosticMessage msg : messages) {
      switch (msg.getKind()) {
        case INFO:
          log.info(msg.getMessage());
          break;
        case WARN:
          log.warn(msg.getMessage());
          break;
        case ERROR:
          log.error(msg.getMessage());
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
  public void skipSnapshot(String probeId, DebuggerContext.SkipCause cause) {
    String causeTag;
    switch (cause) {
      case RATE:
        causeTag = "cause:rate";
        break;
      case CONDITION:
        causeTag = "cause:condition";
        break;
      default:
        throw new IllegalArgumentException("Unknown cause: " + cause);
    }
    String probeIdTag = "probe_id:" + probeId;
    debuggerMetrics.incrementCounter(PREFIX + "skip", causeTag, probeIdTag);
  }

  long getCurrentFlushInterval() {
    return currentFlushInterval;
  }
}
