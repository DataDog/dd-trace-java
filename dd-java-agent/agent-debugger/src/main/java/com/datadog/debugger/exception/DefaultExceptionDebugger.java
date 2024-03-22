package com.datadog.debugger.exception;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.EXCEPTION;
import static com.datadog.debugger.util.ExceptionHelper.createThrowableMapping;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.exception.ExceptionProbeManager.ThrowableState;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.AgentTaskScheduler;
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
  public static final String ERROR_DEBUG_INFO_CAPTURED = "error.debug_info_captured";
  public static final String SNAPSHOT_ID_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.snapshot_id";

  private final ExceptionProbeManager exceptionProbeManager;
  private final ConfigurationUpdater configurationUpdater;
  private final ClassNameFiltering classNameFiltering;

  public DefaultExceptionDebugger(
      ConfigurationUpdater configurationUpdater, ClassNameFiltering classNameFiltering) {
    this(new ExceptionProbeManager(classNameFiltering), configurationUpdater, classNameFiltering);
  }

  DefaultExceptionDebugger(
      ExceptionProbeManager exceptionProbeManager,
      ConfigurationUpdater configurationUpdater,
      ClassNameFiltering classNameFiltering) {
    this.exceptionProbeManager = exceptionProbeManager;
    this.configurationUpdater = configurationUpdater;
    this.classNameFiltering = classNameFiltering;
  }

  @Override
  public void handleException(Throwable t, AgentSpan span) {
    String fingerprint = Fingerprinter.fingerprint(t, classNameFiltering);
    if (fingerprint == null) {
      LOGGER.debug("Unable to fingerprint exception", t);
      return;
    }
    Throwable innerMostException = ExceptionHelper.getInnerMostThrowable(t);
    if (innerMostException == null) {
      LOGGER.debug("Unable to find root cause of exception");
      return;
    }
    if (exceptionProbeManager.isAlreadyInstrumented(fingerprint)) {
      ThrowableState state = exceptionProbeManager.getSateByThrowable(innerMostException);
      if (state == null) {
        LOGGER.debug("Unable to find state for throwable: {}", innerMostException.toString());
        return;
      }
      processSnapshotsAndSetTags(t, span, state, innerMostException);
    } else {
      exceptionProbeManager.createProbesForException(
          fingerprint, innerMostException.getStackTrace());
      AgentTaskScheduler.INSTANCE.execute(() -> applyExceptionConfiguration(fingerprint));
    }
  }

  private void applyExceptionConfiguration(String fingerprint) {
    configurationUpdater.accept(EXCEPTION, exceptionProbeManager.getProbes());
    exceptionProbeManager.addFingerprint(fingerprint);
  }

  private static void processSnapshotsAndSetTags(
      Throwable t, AgentSpan span, ThrowableState state, Throwable innerMostException) {
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
    int[] mapping = createThrowableMapping(innerMostException, t);
    StackTraceElement[] innerTrace = innerMostException.getStackTrace();
    int currentIdx = 0;
    boolean snapshotAssigned = false;
    List<Snapshot> snapshots = state.getSnapshots();
    for (int i = 0; i < snapshots.size(); i++) {
      Snapshot snapshot = snapshots.get(i);
      String className = snapshot.getProbe().getLocation().getType();
      String methodName = snapshot.getProbe().getLocation().getMethod();
      while (currentIdx < innerTrace.length
          && !innerTrace[currentIdx].getClassName().equals(className)
          && !innerTrace[currentIdx].getMethodName().equals(methodName)) {
        currentIdx++;
      }
      int frameIndex = mapping[currentIdx++];
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
    }
  }

  ExceptionProbeManager getExceptionProbeManager() {
    return exceptionProbeManager;
  }
}
