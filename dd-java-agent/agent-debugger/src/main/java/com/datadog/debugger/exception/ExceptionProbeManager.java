package com.datadog.debugger.exception;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.WeakIdentityHashMap;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages the probes used for instrumentation of exception stacktraces. */
public class ExceptionProbeManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionProbeManager.class);

  private final Map<String, Instant> fingerprints = new ConcurrentHashMap<>();
  private final Map<String, ExceptionProbe> probes = new ConcurrentHashMap<>();
  private final ClassNameFilter classNameFiltering;
  // FIXME: if this becomes a bottleneck, find a way to make it concurrent weak identity hashmap
  private final Map<Throwable, ThrowableState> snapshotsByThrowable =
      Collections.synchronizedMap(new WeakIdentityHashMap<>());
  private final long captureIntervalS;
  private final Clock clock;
  private final int maxCapturedFrames;

  public ExceptionProbeManager(ClassNameFilter classNameFiltering, Duration captureInterval) {
    this(
        classNameFiltering,
        captureInterval,
        Clock.systemUTC(),
        Config.get().getDebuggerExceptionMaxCapturedFrames());
  }

  ExceptionProbeManager(ClassNameFilter classNameFiltering) {
    this(
        classNameFiltering,
        Duration.ofHours(1),
        Clock.systemUTC(),
        Config.get().getDebuggerExceptionMaxCapturedFrames());
  }

  ExceptionProbeManager(
      ClassNameFilter classNameFiltering,
      Duration captureInterval,
      Clock clock,
      int maxCapturedFrames) {
    this.classNameFiltering = classNameFiltering;
    this.captureIntervalS = captureInterval.getSeconds();
    this.clock = clock;
    this.maxCapturedFrames = maxCapturedFrames;
  }

  public ClassNameFilter getClassNameFilter() {
    return classNameFiltering;
  }

  static class CreationResult {
    final int probesCreated;
    final int thirdPartyFrames;
    final int nativeFrames;

    public CreationResult(int probesCreated, int thirdPartyFrames, int nativeFrames) {
      this.probesCreated = probesCreated;
      this.thirdPartyFrames = thirdPartyFrames;
      this.nativeFrames = nativeFrames;
    }
  }

  public CreationResult createProbesForException(
      StackTraceElement[] stackTraceElements, int chainedExceptionIdx) {
    int instrumentedFrames = 0;
    int nativeFrames = 0;
    int thirdPartyFrames = 0;
    for (StackTraceElement stackTraceElement : stackTraceElements) {
      if (instrumentedFrames >= maxCapturedFrames) {
        break;
      }
      if (stackTraceElement.isNativeMethod() || stackTraceElement.getLineNumber() < 0) {
        // Skip native methods and lines without line numbers
        nativeFrames++;
        continue;
      }
      if (classNameFiltering.isExcluded(stackTraceElement.getClassName())) {
        thirdPartyFrames++;
        continue;
      }
      Where where =
          Where.of(
              stackTraceElement.getClassName(),
              stackTraceElement.getMethodName(),
              null,
              String.valueOf(stackTraceElement.getLineNumber()));
      ExceptionProbe probe = createMethodProbe(this, where, chainedExceptionIdx);
      probes.putIfAbsent(probe.getId(), probe);
      instrumentedFrames++;
    }
    return new CreationResult(instrumentedFrames, thirdPartyFrames, nativeFrames);
  }

  void addFingerprint(String fingerprint) {
    fingerprints.put(fingerprint, Instant.MIN);
  }

  private static ExceptionProbe createMethodProbe(
      ExceptionProbeManager exceptionProbeManager, Where where, int chainedExceptionIdx) {
    String probeId = UUID.randomUUID().toString();
    return new ExceptionProbe(
        new ProbeId(probeId, 0),
        where,
        null,
        null,
        null,
        exceptionProbeManager,
        chainedExceptionIdx);
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.containsKey(fingerprint);
  }

  public Collection<ExceptionProbe> getProbes() {
    return probes.values();
  }

  public Map<String, Instant> getFingerprints() {
    return fingerprints;
  }

  public boolean shouldCaptureException(String fingerprint) {
    return shouldCaptureException(fingerprint, clock);
  }

  boolean shouldCaptureException(String fingerprint, Clock clock) {
    Instant lastCapture = fingerprints.get(fingerprint);
    if (lastCapture == null) {
      return false;
    }
    return ChronoUnit.SECONDS.between(lastCapture, Instant.now(clock)) >= captureIntervalS;
  }

  public void addSnapshot(Snapshot snapshot) {
    Throwable throwable = snapshot.getCaptures().getReturn().getCapturedThrowable().getThrowable();
    if (throwable == null) {
      LOGGER.debug("Snapshot has no throwable: {}", snapshot.getId());
      return;
    }
    throwable = ExceptionHelper.getInnerMostThrowable(throwable);
    if (throwable == null) {
      throwable = snapshot.getCaptures().getReturn().getCapturedThrowable().getThrowable();
      LOGGER.debug("Unable to find root cause of exception: {}", String.valueOf(throwable));
      return;
    }
    ThrowableState state =
        snapshotsByThrowable.computeIfAbsent(
            throwable, key -> new ThrowableState(UUID.randomUUID().toString()));
    snapshot.setExceptionId(state.getExceptionId());
    state.addSnapshot(snapshot);
  }

  public ThrowableState getStateByThrowable(Throwable throwable) {
    return snapshotsByThrowable.get(throwable);
  }

  public void updateLastCapture(String fingerprint) {
    updateLastCapture(fingerprint, clock);
  }

  void updateLastCapture(String fingerprint, Clock clock) {
    fingerprints.put(fingerprint, Instant.now(clock));
  }

  boolean hasExceptionStateTracked() {
    return !snapshotsByThrowable.isEmpty();
  }

  public static class ThrowableState {
    private final String exceptionId;
    private List<Snapshot> snapshots;

    private ThrowableState(String exceptionId) {
      this.exceptionId = exceptionId;
    }

    public String getExceptionId() {
      return exceptionId;
    }

    public List<Snapshot> getSnapshots() {
      return snapshots;
    }

    public boolean isSampling() {
      return snapshots != null && !snapshots.isEmpty();
    }

    public void addSnapshot(Snapshot snapshot) {
      if (snapshots == null) {
        snapshots = new ArrayList<>();
      }
      snapshots.add(snapshot);
    }
  }
}
