package com.datadog.debugger.probe;

import static com.datadog.debugger.util.ExceptionHelper.getInnerMostThrowable;
import static java.util.Collections.emptyList;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.exception.ExceptionProbeManager;
import com.datadog.debugger.exception.Fingerprinter;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.ExceptionInstrumentor;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExceptionProbe extends LogProbe implements ForceMethodInstrumentation {
  private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionProbe.class);
  private final transient ExceptionProbeManager exceptionProbeManager;
  private final transient int chainedExceptionIdx;

  public ExceptionProbe(
      ProbeId probeId,
      Where where,
      Capture capture,
      Sampling sampling,
      ExceptionProbeManager exceptionProbeManager,
      int chainedExceptionIdx) {
    super(
        LANGUAGE,
        probeId,
        null,
        where,
        MethodLocation.EXIT,
        null,
        null,
        true,
        // forcing a useless condition to be instrumented with captureEntry=false
        new ProbeCondition(DSL.when(DSL.TRUE), "true"),
        capture,
        sampling);
    this.exceptionProbeManager = exceptionProbeManager;
    this.chainedExceptionIdx = chainedExceptionIdx;
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<Integer> probeIndices) {
    return new ExceptionInstrumentor(this, methodInfo, diagnostics, probeIndices).instrument();
  }

  @Override
  public boolean isLineProbe() {
    // Exception probe are always method probe even if there is a line number
    return false;
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new ExceptionProbeStatus(this);
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {
    ExceptionProbeStatus exceptionStatus;
    if (status instanceof ExceptionProbeStatus) {
      exceptionStatus = (ExceptionProbeStatus) status;
      exceptionStatus.setCapture(false);
    } else {
      throw new IllegalStateException("Invalid status: " + status.getClass());
    }
    if (methodLocation != MethodLocation.EXIT) {
      return;
    }
    if (context.getCapturedThrowable() == null) {
      return;
    }
    Throwable throwable = context.getCapturedThrowable().getThrowable();
    if (throwable == null) {
      LOGGER.debug("Throwable cleared by GC");
      return;
    }
    Throwable innerMostThrowable = getInnerMostThrowable(throwable);
    String fingerprint =
        Fingerprinter.fingerprint(innerMostThrowable, exceptionProbeManager.getClassNameFilter());
    if (exceptionProbeManager.shouldCaptureException(fingerprint)) {
      LOGGER.debug("Capturing exception matching fingerprint: {}", fingerprint);
      // capture only on uncaught exception matching the fingerprint
      ExceptionProbeManager.ThrowableState state =
          exceptionProbeManager.getStateByThrowable(innerMostThrowable);
      if (state != null) {
        // Already unwinding the exception
        if (!state.isSampling()) {
          // skip snapshot because no snapshot from previous stack level
          return;
        }
        // Force for coordinated sampling
        exceptionStatus.setForceSampling(true);
      }
      exceptionStatus.setCapture(true);
      super.evaluate(context, status, methodLocation);
    }
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
      // clear any strong ref to the exception before adding the snapshot to avoid leaking snapshots
      // inside the stateByThrowable map
      clearExceptionRefs(snapshot);
      // add snapshot for later to wait for triggering point (ExceptionDebugger::handleException)
      snapshot.setChainedExceptionIdx(chainedExceptionIdx);
      exceptionProbeManager.addSnapshot(snapshot);
      LOGGER.debug(
          "committing exception probe id={}, snapshot id={}, exception id={}",
          id,
          snapshot.getId(),
          snapshot.getExceptionId());
    }
  }

  private void clearExceptionRefs(Snapshot snapshot) {
    snapshot.getCaptures().getReturn().getLocals().remove(ValueReferences.EXCEPTION_REF);
    snapshot.getCaptures().getReturn().removeExtension(ValueReferences.EXCEPTION_EXTENSION_NAME);
  }

  @Override
  public void buildLocation(MethodInfo methodInfo) {
    String type = where.getTypeName();
    String method = where.getMethodName();
    if (methodInfo != null) {
      type = methodInfo.getTypeName();
      method = methodInfo.getMethodName();
    }
    // drop line number for exception probe
    this.location = new ProbeLocation(type, method, where.getSourceFile(), emptyList());
  }

  public static class ExceptionProbeStatus extends LogStatus {
    private boolean capture = true; // default to true for status entry when mixed with log probe

    public ExceptionProbeStatus(ProbeImplementation probeImplementation) {
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
