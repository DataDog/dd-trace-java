package com.datadog.debugger.exception;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.EXCEPTION;
import static com.datadog.debugger.util.ExceptionHelper.createThrowableMapping;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract implementation of {@link DebuggerContext.ExceptionDebugger} that uses {@link
 * ExceptionProbeManager} to instrument the exception stacktrace and send snapshots.
 */
public abstract class AbstractExceptionDebugger implements DebuggerContext.ExceptionDebugger {
  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractExceptionDebugger.class);
  public static final String DD_DEBUG_ERROR_PREFIX = "_dd.debug.error.";
  public static final String DD_DEBUG_ERROR_EXCEPTION_ID = DD_DEBUG_ERROR_PREFIX + "exception_id";
  public static final String DD_DEBUG_ERROR_EXCEPTION_HASH =
      DD_DEBUG_ERROR_PREFIX + "exception_hash";
  public static final String SNAPSHOT_ID_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.snapshot_id";

  private final ExceptionProbeManager exceptionProbeManager;
  private final ConfigurationUpdater configurationUpdater;
  private final DebuggerContext.ClassNameFilter classNameFiltering;
  private final int maxCapturedFrames;
  private final boolean applyConfigAsync;

  AbstractExceptionDebugger(
      ExceptionProbeManager exceptionProbeManager,
      ConfigurationUpdater configurationUpdater,
      DebuggerContext.ClassNameFilter classNameFiltering,
      int maxCapturedFrames,
      boolean applyConfigAsync) {
    this.exceptionProbeManager = exceptionProbeManager;
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
    this.maxCapturedFrames = maxCapturedFrames;
    this.applyConfigAsync = applyConfigAsync;
  }

  protected abstract boolean shouldHandleException(Throwable t, AgentSpan span);

  @Override
  public void handleException(Throwable t, AgentSpan span) {
    if (!shouldHandleException(t, span)) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Skip handling error: {}", t.toString());
      }
      return;
    }

    String fingerprint = Fingerprinter.fingerprint(t, classNameFiltering);
    if (fingerprint == null) {
      LOGGER.debug("Unable to fingerprint exception", t);
      return;
    }
    Deque<Throwable> chainedExceptions = new ArrayDeque<>();
    Throwable innerMostException = ExceptionHelper.getInnerMostThrowable(t, chainedExceptions);
    if (innerMostException == null) {
      LOGGER.debug("Unable to find root cause of exception");
      return;
    }
    List<Throwable> chainedExceptionsList = new ArrayList<>(chainedExceptions);
    if (exceptionProbeManager.isAlreadyInstrumented(fingerprint)) {
      ExceptionProbeManager.ThrowableState state =
          exceptionProbeManager.getStateByThrowable(innerMostException);
      if (state == null) {
        LOGGER.debug("Unable to find state for throwable: {}", innerMostException.toString());
        return;
      }
      processSnapshotsAndSetTags(
          t, span, state, chainedExceptionsList, fingerprint, maxCapturedFrames);
      exceptionProbeManager.updateLastCapture(fingerprint);
    } else {
      // climb up the exception chain to find the first exception that has instrumented frames
      Throwable throwable;
      int chainedExceptionIdx = 0;
      while ((throwable = chainedExceptions.pollFirst()) != null) {
        ExceptionProbeManager.CreationResult creationResult =
            exceptionProbeManager.createProbesForException(
                throwable.getStackTrace(), chainedExceptionIdx);
        if (creationResult.probesCreated > 0) {
          if (!applyConfigAsync) {
            applyExceptionConfiguration(fingerprint);
          } else {
            AgentTaskScheduler.get().execute(() -> applyExceptionConfiguration(fingerprint));
          }
          break;
        } else {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "No probe created, nativeFrames={}, thirdPartyFrames={} for exception: {}",
                creationResult.nativeFrames,
                creationResult.thirdPartyFrames,
                ExceptionHelper.foldExceptionStackTrace(throwable));
          }
        }
        chainedExceptionIdx++;
      }
    }
  }

  private void applyExceptionConfiguration(String fingerprint) {
    configurationUpdater.accept(EXCEPTION, exceptionProbeManager.getProbes());
    exceptionProbeManager.addFingerprint(fingerprint);
  }

  protected void addStackFrameTags(
      AgentSpan span, Snapshot snapshot, int frameIndex, StackTraceElement stackFrame) {
    String tagName = String.format(SNAPSHOT_ID_TAG_FMT, frameIndex);
    span.setTag(tagName, snapshot.getId());
    LOGGER.debug("add tag to span[{}]: {}: {}", span.getSpanId(), tagName, snapshot.getId());
  }

  private void processSnapshotsAndSetTags(
      Throwable t,
      AgentSpan span,
      ExceptionProbeManager.ThrowableState state,
      List<Throwable> chainedExceptions,
      String fingerprint,
      int maxCapturedFrames) {
    if (span.getTag(DD_DEBUG_ERROR_EXCEPTION_ID) != null) {
      LOGGER.debug("Clear previous frame tags");
      // already set for this span, clear the frame tags
      span.getTags()
          .forEach(
              (k, v) -> {
                if (k.startsWith(DD_DEBUG_ERROR_PREFIX)) {
                  span.setTag(k, (String) null);
                }
              });
    }
    boolean snapshotAssigned = false;
    List<Snapshot> snapshots = state.getSnapshots();
    int maxSnapshotSize = Math.min(snapshots.size(), maxCapturedFrames);
    for (int i = 0; i < maxSnapshotSize; i++) {
      Snapshot snapshot = snapshots.get(i);
      int chainedExceptionIdx = snapshot.getChainedExceptionIdx();
      if (chainedExceptionIdx >= chainedExceptions.size()) {
        LOGGER.debug(
            "Chained exception for snapshot={} is out of bounds: {}/{}",
            snapshot.getId(),
            chainedExceptionIdx,
            chainedExceptions.size());
        continue;
      }
      Throwable currentEx = chainedExceptions.get(snapshot.getChainedExceptionIdx());
      int[] mapping = createThrowableMapping(currentEx, t);
      StackTraceElement[] innerTrace = currentEx.getStackTrace();
      int currentIdx = innerTrace.length - snapshot.getStack().size();
      if (!sanityCheckSnapshotAssignment(snapshot, innerTrace, currentIdx)) {
        continue;
      }
      int frameIndex = mapping[currentIdx];
      if (frameIndex == -1) {
        continue;
      }

      addStackFrameTags(span, snapshot, frameIndex, innerTrace[currentIdx]);

      if (!state.isSnapshotSent()) {
        DebuggerAgent.getSink().addSnapshot(snapshot);
      }
      snapshotAssigned = true;
    }
    if (snapshotAssigned) {
      state.markAsSnapshotSent();
      span.setTag(DD_DEBUG_ERROR_EXCEPTION_ID, state.getExceptionId());
      LOGGER.debug(
          "add tag to span[{}]: {}: {}",
          span.getSpanId(),
          DD_DEBUG_ERROR_EXCEPTION_ID,
          state.getExceptionId());
      span.setTag(Tags.ERROR_DEBUG_INFO_CAPTURED, true);
      span.setTag(DD_DEBUG_ERROR_EXCEPTION_HASH, fingerprint);
    }
  }

  private static boolean sanityCheckSnapshotAssignment(
      Snapshot snapshot, StackTraceElement[] innerTrace, int currentIdx) {
    String className = snapshot.getProbe().getLocation().getType();
    String methodName = snapshot.getProbe().getLocation().getMethod();
    if (!className.equals(innerTrace[currentIdx].getClassName())
        || !methodName.equals(innerTrace[currentIdx].getMethodName())) {
      LOGGER.warn("issue when assigning snapshot to frame: {} {}", className, methodName);
      return false;
    }
    return true;
  }

  public ExceptionProbeManager getExceptionProbeManager() {
    return exceptionProbeManager;
  }
}
