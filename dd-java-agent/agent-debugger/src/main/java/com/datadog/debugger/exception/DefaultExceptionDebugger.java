package com.datadog.debugger.exception;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.EXCEPTION;
import static com.datadog.debugger.util.ExceptionHelper.createThrowableMapping;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.exception.ExceptionProbeManager.ThrowableState;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.CircuitBreaker;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
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
  public static final String SNAPSHOT_ID_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.snapshot_id";

  // Test Optimization / Failed Test Replay specific
  public static final String TEST_DEBUG_ERROR_FILE_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.file";
  public static final String TEST_DEBUG_ERROR_LINE_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.line";

  private final ExceptionProbeManager exceptionProbeManager;
  private final ConfigurationUpdater configurationUpdater;
  private final ClassNameFilter classNameFiltering;
  private final CircuitBreaker circuitBreaker;
  private final int maxCapturedFrames;

  public DefaultExceptionDebugger(
      ConfigurationUpdater configurationUpdater,
      ClassNameFilter classNameFiltering,
      Duration captureInterval,
      int maxExceptionPerSecond,
      int maxCapturedFrames) {
    this(
        new ExceptionProbeManager(classNameFiltering, captureInterval),
        configurationUpdater,
        classNameFiltering,
        maxExceptionPerSecond,
        maxCapturedFrames);
  }

  DefaultExceptionDebugger(
      ExceptionProbeManager exceptionProbeManager,
      ConfigurationUpdater configurationUpdater,
      ClassNameFilter classNameFiltering,
      int maxExceptionPerSecond,
      int maxCapturedFrames) {
    this.exceptionProbeManager = exceptionProbeManager;
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
    this.circuitBreaker = new CircuitBreaker(maxExceptionPerSecond, Duration.ofSeconds(1));
    this.maxCapturedFrames = maxCapturedFrames;
  }

  @Override
  public void handleException(Throwable t, AgentSpan span) {
    // CIVIS Failed Test Replay acts on errors
    if (t instanceof Error && !Config.get().isCiVisibilityFailedTestReplayActive()) {
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
          if (Config.get().isCiVisibilityFailedTestReplayActive()) {
            // Assume Exception Replay is working under Failed Test Replay logic,
            // instrumentation applied sync for immediate test retries
            applyExceptionConfiguration(fingerprint);
          } else {
            AgentTaskScheduler.INSTANCE.execute(() -> applyExceptionConfiguration(fingerprint));
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

  private static void processSnapshotsAndSetTags(
      Throwable t,
      AgentSpan span,
      ThrowableState state,
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

      if (Config.get().isCiVisibilityFailedTestReplayActive()) {
        StackTraceElement stackFrame = innerTrace[currentIdx];
        String fileTag = String.format(TEST_DEBUG_ERROR_FILE_TAG_FMT, frameIndex);
        String lineTag = String.format(TEST_DEBUG_ERROR_LINE_TAG_FMT, frameIndex);
        span.setTag(fileTag, stackFrame.getFileName());
        span.setTag(lineTag, stackFrame.getLineNumber());

        LOGGER.debug(
            "add ftr debug tags to span[{}]: {}={}, {}={}",
            span.getSpanId(),
            fileTag,
            stackFrame.getFileName(),
            lineTag,
            stackFrame.getLineNumber());
      }

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
