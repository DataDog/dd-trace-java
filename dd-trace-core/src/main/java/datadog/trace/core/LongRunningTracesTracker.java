package datadog.trace.core;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LongRunningTracesTracker {
  private static final Logger LOGGER = LoggerFactory.getLogger(LongRunningTracesTracker.class);

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
              + "Long running traces will not be tracked.",
          "dd." + TracerConfig.TRACE_LONG_RUNNING_ENABLED,
          features.getVersion() != null ? features.getVersion() : "unknown");
    }
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

  private void addTrace(PendingTrace trace) {
    if (trace.empty()) {
      return;
    }
    if (traceArray.size() == maxTrackedTraces) {
      dropped++;
      return;
    }
    traceArray.add(trace);
  }

  public void flushAndCompact(long nowMilli) {
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
      if (trace.empty() || !features.supportsLongRunning()) {
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
          cleanSlot(i);
          continue;
        }
        trace.compareAndSetLongRunningState(TRACKED, WRITE_RUNNING_SPANS);
        write++;
        trace.write();
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
    healthMetrics.onLongRunningUpdate(dropped, write, expired);
    dropped = 0;
    write = 0;
    expired = 0;
  }
}
