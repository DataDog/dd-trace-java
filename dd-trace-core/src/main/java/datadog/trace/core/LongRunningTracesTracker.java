package datadog.trace.core;

import static java.util.Comparator.comparingLong;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.common.writer.TraceDumpJsonExporter;
import datadog.trace.core.monitor.HealthMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LongRunningTracesTracker implements TracerFlare.Reporter {
  private static final Logger LOGGER = LoggerFactory.getLogger(LongRunningTracesTracker.class);
  private static final int MAX_DUMPED_TRACES = 50;
  private static final Comparator<PendingTrace> TRACE_BY_START_TIME =
      comparingLong(PendingTrace::getRunningTraceStartTime);

  private final DDAgentFeaturesDiscovery features;
  private final HealthMetrics healthMetrics;
  private long lastFlushMilli = 0;

  private final int maxTrackedTraces;
  private final int initialFlushPeriodMilli;
  private final int flushPeriodMilli;
  private final long maxTrackedDurationMilli = TimeUnit.HOURS.toMillis(12);
  private final List<PendingTrace> traceArray = new ArrayList<>(1 << 4);
  private int dropped = 0;
  private int write = 0;
  private int expired = 0;
  private int droppedSampling = 0;

  public static final int NOT_TRACKED = -1;
  public static final int UNDEFINED = 0;
  public static final int TO_TRACK = 1;
  public static final int TRACKED = 2;
  public static final int WRITE_RUNNING_SPANS = 3;
  public static final int EXPIRED = 4;

  public LongRunningTracesTracker(
      Config config,
      int maxTrackedTraces,
      SharedCommunicationObjects sharedCommunicationObjects,
      HealthMetrics healthMetrics) {
    this.maxTrackedTraces = maxTrackedTraces;
    this.initialFlushPeriodMilli =
        (int) TimeUnit.SECONDS.toMillis(config.getLongRunningTraceInitialFlushInterval());
    this.flushPeriodMilli =
        (int) TimeUnit.SECONDS.toMillis(config.getLongRunningTraceFlushInterval());
    this.features = sharedCommunicationObjects.featuresDiscovery(config);
    this.healthMetrics = healthMetrics;

    if (!features.supportsLongRunning()) {
      LOGGER.warn(
          "Long running trace tracking is enabled via {}, however the Datadog Agent version {} does not support receiving long running traces. "
              + "Long running traces will be tracked locally in memory (up to {} traces) but will NOT be automatically reported to the agent. "
              + "Long running traces are included in tracer flares.",
          "dd." + TracerConfig.TRACE_LONG_RUNNING_ENABLED,
          features.getVersion() != null ? features.getVersion() : "unknown",
          maxTrackedTraces);
    }

    TracerFlare.addReporter(this);
  }

  public boolean add(PendingTraceBuffer.Element element) {
    if (!(element instanceof PendingTrace)) {
      return false;
    }
    PendingTrace trace = (PendingTrace) element;
    // PendingTraces are added only once
    if (!trace.compareAndSetLongRunningState(TO_TRACK, TRACKED)) {
      return false;
    }
    this.addTrace(trace);
    return true;
  }

  private synchronized void addTrace(PendingTrace trace) {
    if (trace.empty()) {
      return;
    }
    if (traceArray.size() == maxTrackedTraces) {
      dropped++;
      return;
    }
    traceArray.add(trace);
  }

  public synchronized void flushAndCompact(long nowMilli) {
    if (nowMilli < lastFlushMilli + TimeUnit.SECONDS.toMillis(1)) {
      return;
    }
    int i = 0;
    while (i < traceArray.size()) {
      PendingTrace trace = traceArray.get(i);
      if (trace == null) {
        cleanSlot(i);
        continue;
      }
      if (trace.empty()) {
        trace.compareAndSetLongRunningState(WRITE_RUNNING_SPANS, NOT_TRACKED);
        cleanSlot(i);
        continue;
      }
      if (hasExpired(nowMilli, trace)) {
        trace.compareAndSetLongRunningState(WRITE_RUNNING_SPANS, EXPIRED);
        expired++;
        cleanSlot(i);
        continue;
      }
      if (shouldFlush(nowMilli, trace)) {
        if (negativeOrNullPriority(trace)) {
          trace.compareAndSetLongRunningState(TRACKED, NOT_TRACKED);
          droppedSampling++;
          cleanSlot(i);
          continue;
        }
        if (features.supportsLongRunning()) {
          trace.compareAndSetLongRunningState(TRACKED, WRITE_RUNNING_SPANS);
          write++;
          trace.write();
        }
      }
      i++;
    }
    lastFlushMilli = nowMilli;
    flushStats();
  }

  private boolean hasExpired(long nowMilli, PendingTrace trace) {
    return (nowMilli - TimeUnit.NANOSECONDS.toMillis(trace.getRunningTraceStartTime()))
        > maxTrackedDurationMilli;
  }

  private boolean shouldFlush(long nowMilli, PendingTrace trace) {
    long traceStartTimeNano = trace.getRunningTraceStartTime();
    long lastWriteTimeNano = trace.getLastWriteTime();

    // Initial flush
    if (lastWriteTimeNano <= traceStartTimeNano) {
      return nowMilli - TimeUnit.NANOSECONDS.toMillis(traceStartTimeNano) > initialFlushPeriodMilli;
    }

    return nowMilli - TimeUnit.NANOSECONDS.toMillis(lastWriteTimeNano) > flushPeriodMilli;
  }

  private void cleanSlot(int index) {
    int lastElementIndex = traceArray.size() - 1;
    traceArray.set(index, traceArray.get(lastElementIndex));
    traceArray.remove(lastElementIndex);
  }

  private boolean negativeOrNullPriority(PendingTrace trace) {
    Integer prio = trace.evaluateSamplingPriority();
    return prio == null || prio <= 0;
  }

  private void flushStats() {
    healthMetrics.onLongRunningUpdate(dropped, write, expired, droppedSampling);
    dropped = 0;
    write = 0;
    expired = 0;
    droppedSampling = 0;
  }

  public String getTracesAsJson() {
    try (TraceDumpJsonExporter writer = new TraceDumpJsonExporter()) {
      List<PendingTrace> traces;
      synchronized (this) {
        traces = new ArrayList<>(traceArray);
      }
      traces.sort(TRACE_BY_START_TIME);

      int limit = Math.min(traces.size(), MAX_DUMPED_TRACES);
      for (int i = 0; i < limit; i++) {
        writer.write(traces.get(i).getSpans());
      }
      return writer.getDumpJson();
    }
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    TracerFlare.addText(zip, "long_running_traces.txt", getTracesAsJson());
  }
}
