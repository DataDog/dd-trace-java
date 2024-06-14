package com.datadog.debugger.probe;

import static com.datadog.debugger.util.ExceptionHelper.getInnerMostThrowable;
import static java.util.Collections.emptyList;

import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.snapshot.SnapshotProbeManager;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugSnapshotProbe extends LogProbe {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebugSnapshotProbe.class);
  private final transient SnapshotProbeManager probeManager;

  public DebugSnapshotProbe(ProbeId probeId, Where where, SnapshotProbeManager probeManager) {
    super(LANGUAGE, probeId, null, where, MethodLocation.EXIT, null, null, true, null, null, null);
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
    boolean shouldCommit = fillSnapshot(entryContext, exitContext, caughtExceptions, snapshot);
    if (shouldCommit) {
      /*
       * Record stack trace having the caller of this method as 'top' frame.
       * For this it is necessary to discard:
       * - Thread.currentThread().getStackTrace()
       * - Snapshot.recordStackTrace()
       * - ExceptionProbe.commit()
       * - DebuggerContext.commit()
       */
      snapshot.recordStackTrace(4);
      // add snapshot for later to wait for triggering point (ExceptionDebugger::handleException)
      LOGGER.debug(
          "committing exception probe id={}, snapshot id={}, exception id={}",
          id,
          snapshot.getId(),
          snapshot.getExceptionId());
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
    private boolean capture;

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
