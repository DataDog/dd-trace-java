package com.datadog.debugger.exception;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.EXCEPTION;
import static com.datadog.debugger.util.ExceptionHelper.createThrowableMapping;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.exception.ExceptionProbeManager.ThrowableState;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.CircuitBreaker;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link DebuggerContext.ExceptionDebugger} that uses {@link
 * ExceptionProbeManager} to instrument the exception stacktrace and send snapshots.
 */
public class DefaultExceptionDebugger implements DebuggerContext.ExceptionDebugger {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultExceptionDebugger.class);
  public static final String DD_DEBUG_ERROR_PREFIX = "_dd.debug.error.";
  public static final String DD_DEBUG_ERROR_EXCEPTION_ID = DD_DEBUG_ERROR_PREFIX + "exception_id";
  public static final String DD_DEBUG_ERROR_EXCEPTION_HASH =
      DD_DEBUG_ERROR_PREFIX + "exception_hash";
  public static final String ERROR_DEBUG_INFO_CAPTURED = "error.debug_info_captured";
  public static final String SNAPSHOT_ID_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.snapshot_id";

  private final ExceptionProbeManager exceptionProbeManager;
  private final ConfigurationUpdater configurationUpdater;
  private final ClassNameFilter classNameFiltering;
  private final CircuitBreaker circuitBreaker;

  public DefaultExceptionDebugger(
      ConfigurationUpdater configurationUpdater,
      ClassNameFilter classNameFiltering,
      Duration captureInterval,
      int maxExceptionPerSecond) {
    this(
        new ExceptionProbeManager(classNameFiltering, captureInterval),
        configurationUpdater,
        classNameFiltering,
        maxExceptionPerSecond);
  }

  DefaultExceptionDebugger(
      ExceptionProbeManager exceptionProbeManager,
      ConfigurationUpdater configurationUpdater,
      ClassNameFilter classNameFiltering,
      int maxExceptionPerSecond) {
    this.exceptionProbeManager = exceptionProbeManager;
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
    this.circuitBreaker = new CircuitBreaker(maxExceptionPerSecond, Duration.ofSeconds(1));
  }

  @Override
  public void handleException(Throwable t, AgentSpan span) {
    if (t instanceof Error) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Skip handling error: {}", t.toString());
      }
      return;
    }
    if (!circuitBreaker.trip()) {
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
      ThrowableState state = exceptionProbeManager.getStateByThrowable(innerMostException);
      if (state == null) {
        LOGGER.debug("Unable to find state for throwable: {}", innerMostException.toString());
        return;
      }
      processSnapshotsAndSetTags(t, span, state, chainedExceptionsList, fingerprint);
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
          AgentTaskScheduler.INSTANCE.execute(() -> applyExceptionConfiguration(fingerprint));
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

  private static void processSnapshotsAndSetTags(
      Throwable t,
      AgentSpan span,
      ThrowableState state,
      List<Throwable> chainedExceptions,
      String fingerprint) {
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
    for (int i = 0; i < snapshots.size(); i++) {
      Snapshot snapshot = snapshots.get(i);
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
      String tagName = String.format(SNAPSHOT_ID_TAG_FMT, frameIndex);
      span.setTag(tagName, snapshot.getId());
      LOGGER.debug("add tag to span[{}]: {}: {}", span.getSpanId(), tagName, snapshot.getId());
      DebuggerAgent.getSink().addSnapshot(snapshot);
      snapshotAssigned = true;
    }
    if (snapshotAssigned) {
      span.setTag(DD_DEBUG_ERROR_EXCEPTION_ID, state.getExceptionId());
      LOGGER.debug(
          "add tag to span[{}]: {}: {}",
          span.getSpanId(),
          DD_DEBUG_ERROR_EXCEPTION_ID,
          state.getExceptionId());
      span.setTag(ERROR_DEBUG_INFO_CAPTURED, true);
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
