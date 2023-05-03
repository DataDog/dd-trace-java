package datadog.trace.core;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class LongRunningTracesTracker {
  private final DDAgentFeaturesDiscovery features;
  private long lastFlushMilli = 0;

  private final int maxTrackedTraces;
  private final int flushPeriodMilli;
  private final long maxTrackedDurationMilli = TimeUnit.HOURS.toMillis(12);
  private final List<PendingTrace> traceArray = new ArrayList<>(1 << 4);
  private int missedAdd = 0;

  public static final int NOT_TRACKED = -1;
  public static final int UNDEFINED = 0;
  public static final int TO_TRACK = 1;
  public static final int TRACKED = 2;
  public static final int WRITE_RUNNING_SPANS = 3;
  public static final int EXPIRED = 4;

  public LongRunningTracesTracker(
      Config config, int maxTrackedTraces, SharedCommunicationObjects sharedCommunicationObjects) {
    this.maxTrackedTraces = maxTrackedTraces;
    this.flushPeriodMilli = (int) TimeUnit.SECONDS.toMillis(config.getLongRunningFlushInterval());
    this.features = sharedCommunicationObjects.featuresDiscovery(config);
  } // TODO flush missedAdd stats in health check class

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
    if (trace == null || trace.empty()) {
      return;
    }
    if (traceArray.size() == maxTrackedTraces) {
      missedAdd++;
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
      if (expired(nowMilli, trace)) {
        trace.compareAndSetLongRunningState(WRITE_RUNNING_SPANS, EXPIRED);
        cleanSlot(i);
        continue;
      }
      if (shouldFlush(nowMilli, trace)) {
        trace.compareAndSetLongRunningState(TRACKED, WRITE_RUNNING_SPANS);
        trace.write();
      }
      i++;
    }
    lastFlushMilli = nowMilli;
  }

  private boolean expired(long nowMilli, PendingTrace trace) {
    return (nowMilli - TimeUnit.NANOSECONDS.toMillis(trace.getRunningTraceStartTime()))
        > maxTrackedDurationMilli;
  }

  private boolean shouldFlush(long nowMilli, PendingTrace trace) {
    return nowMilli
            - TimeUnit.NANOSECONDS.toMillis(
                Math.max(trace.getRunningTraceStartTime(), trace.getLastWriteTime()))
        > flushPeriodMilli;
  }

  private void cleanSlot(int index) {
    int lastElementIndex = traceArray.size() - 1;
    traceArray.set(index, traceArray.get(lastElementIndex));
    traceArray.remove(lastElementIndex);
  }
}
