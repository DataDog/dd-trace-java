package com.datadog.debugger.probe;

import static com.datadog.debugger.spanorigin.SpanOriginInfo.DD_EXIT_LOCATION_SNAPSHOT_ID;
import static com.datadog.debugger.util.ExceptionHelper.getInnerMostThrowable;
import static java.util.Collections.emptyList;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.snapshot.SnapshotProbeManager;
import com.datadog.debugger.spanorigin.DistDebug;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.PendingTrace;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugSnapshotProbe extends LogProbe {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebugSnapshotProbe.class);

  private final boolean entrySpan;

  private final transient SnapshotProbeManager probeManager;

  public DebugSnapshotProbe(
      ProbeId probeId, boolean entrySpan, Where where, SnapshotProbeManager probeManager) {
    super(LANGUAGE, probeId, null, where, MethodLocation.EXIT, null, null, true, null, null, null);
    this.entrySpan = entrySpan;
    this.probeManager = probeManager;
  }

  @Override
  public boolean isLineProbe() {
    // Exception probe are always method probe even if there is a line number
    return false;
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new DebugSnapshotProbeStatus(this);
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {
    if (!(status instanceof DebugSnapshotProbeStatus)) {
      throw new IllegalStateException("Invalid status: " + status.getClass());
    }
    if (methodLocation != MethodLocation.EXIT) {
      return;
    }
    if (context.getCapturedThrowable() == null) {
      return;
    }
    if (!DistDebug.isDebugEnabled(
        AgentTracer.activeSpan(), DistDebug.CAPTURE_ORIGIN_FRAMES, DistDebug.CAPTURE_ALL_PROBES)) {
      return;
    }

    Throwable throwable = context.getCapturedThrowable().getThrowable();
    Throwable innerMostThrowable = getInnerMostThrowable(throwable);
    String fingerprint =
        Fingerprinter.fingerprint(innerMostThrowable, probeManager.getClassNameFiltering());
    LOGGER.debug("Capturing exception matching fingerprint: {}", fingerprint);
    // capture only on uncaught exception matching the fingerprint
    DebugSnapshotProbeStatus exceptionStatus = (DebugSnapshotProbeStatus) status;
    exceptionStatus.setCapture(true);
    super.evaluate(context, status, methodLocation);
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions) {
    Snapshot snapshot = createSnapshot();
    if (fillSnapshot(entryContext, exitContext, caughtExceptions, snapshot)) {
      snapshot.recordStackTrace(4);
      LOGGER.debug(
          "committing exception probe id={}, snapshot id={}, exception id={}",
          id,
          snapshot.getId(),
          snapshot.getExceptionId());
      AgentSpan span = AgentTracer.activeSpan();
      Map<String, Object> tags = span.getTags();
      boolean hasExitKeys = tags.keySet().stream().anyMatch(s -> s.startsWith("_dd.exit_"));
      String key = entrySpan ? "_dd.entry_location.snapshot_id" : DD_EXIT_LOCATION_SNAPSHOT_ID;

      if (!entrySpan && !hasExitKeys) {
        AgentSpan child = ((PendingTrace) span.context().getTrace()).findSpan(key, probeId.getId());
        if (child != null) {
          span = child;
        }
      }

      span.setTag(key, snapshot.getId());
      if (entrySpan && span.getLocalRootSpan() != null) {
        span.getLocalRootSpan().setTag(key, snapshot.getId());
      }
      commitSnapshot(snapshot, DebuggerAgent.getSink());
    }
  }

  @Override
  public void buildLocation(InstrumentationResult result) {
    String type = where.getTypeName();
    String method = where.getMethodName();
    if (result != null) {
      type = result.getTypeName();
      method = result.getMethodName();
    }
    // drop line number for exception probe
    this.location = new ProbeLocation(type, method, where.getSourceFile(), emptyList());
  }

  public static class DebugSnapshotProbeStatus extends LogStatus {
    private boolean capture = true;

    public DebugSnapshotProbeStatus(ProbeImplementation probeImplementation) {
      super(probeImplementation);
    }

    public void setCapture(boolean capture) {
      this.capture = capture;
    }

    @Override
    public boolean shouldSend() {
      return super.shouldSend() && capture;
    }
  }
}
