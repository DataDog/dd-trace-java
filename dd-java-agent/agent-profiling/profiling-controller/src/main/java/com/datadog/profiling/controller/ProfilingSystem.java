/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.controller;

import static datadog.environment.OperatingSystem.isLinux;
import static datadog.environment.OperatingSystem.isMacOs;
import static datadog.environment.OperatingSystem.isWindows;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AgentThread.PROFILER_RECORDING_SCHEDULER;

import com.datadog.profiling.utils.ProfilingMode;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.profiling.ProfilerFlareLogger;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets up the profiling strategy and schedules the profiling recordings.
 *
 * <p>Lifecycle: {@link #init()} creates the profiler(s) without starting them, {@link #start()}
 * starts recording and the snapshot scheduler, and {@link #stop(boolean)} stops both. The system
 * can be started again after it was stopped, until it is shut down with {@link #shutdown(boolean)}.
 */
public final class ProfilingSystem {
  private static final Logger log = LoggerFactory.getLogger(ProfilingSystem.class);
  static final String RECORDING_NAME = "dd-profiling";

  private static final long TERMINATION_TIMEOUT = 10;

  /**
   * How long, in milliseconds, a stop waits for the scheduler thread, and then for a recording that
   * the thread was creating to be stopped.
   */
  @VisibleForTesting long terminationTimeoutMillis = TimeUnit.SECONDS.toMillis(TERMINATION_TIMEOUT);

  private final ConfigProvider configProvider;
  private final ControllerFactory controllerFactory;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;

  /** Creates the scheduler of each start, since a stopped scheduler can't be restarted. */
  private final Supplier<AgentTaskScheduler> schedulerFactory;

  private final Duration startupDelay;

  /**
   * When the startup delay is over, counted from the creation of the system, i.e. from premain, so
   * that restarting before the first recording doesn't delay it again.
   */
  private final long startupDeadlineNanos;

  private final Duration uploadPeriod;
  private final boolean isStartingFirst;

  /** Created by {@link #init()} and kept across restarts. */
  private Controller controller;

  /** Created by {@link #init()} and kept across restarts. */
  private ControllerContext.Snapshot context;

  /** Set by {@link #start(boolean)} and cleared by {@link #stop(boolean)}. */
  private AgentTaskScheduler scheduler;

  private volatile OngoingRecording recording;
  private volatile SnapshotRecording snapshotRecording;
  private volatile boolean started = false;

  /** Set when a start didn't give a recording, e.g. on the scheduler thread after a delay. */
  private volatile boolean startFailed = false;

  /** Terminal: a shut down system can't be started again. */
  private volatile boolean closed = false;

  /**
   * Guards the lifecycle: init, start, stop, reconfigure and shutdown. A lock rather than a
   * monitor, so that a shutdown without snapshot can tell that a stop holds it instead of waiting
   * for it.
   */
  @VisibleForTesting final ReentrantLock lock = new ReentrantLock();

  /**
   * The stops that hold or wait for {@link #lock}, so that {@link #shutdown} without snapshot
   * doesn't wait behind their final upload.
   */
  private final AtomicInteger pendingStops = new AtomicInteger();

  /**
   * Held while a recording is snapshot, so that it isn't stopped meanwhile: a stop that gets it
   * clears the published recording under it, so that later snapshots skip it, and stops it. A stop
   * doesn't wait for it, since the only snapshots that can be in progress then are those of a
   * scheduler thread that didn't terminate in time, which the stop already waited for: it hands the
   * recording over with {@link #abandonedRecording} instead. The snapshot is uploaded outside of
   * it.
   */
  @VisibleForTesting final ReentrantLock snapshotLock = new ReentrantLock();

  /**
   * A recording that a stop couldn't stop, since it was being snapshot: it is stopped by the first
   * thread that gets {@link #snapshotLock} after it, see {@link #stopAbandonedRecording()}.
   */
  private final AtomicReference<OngoingRecording> abandonedRecording = new AtomicReference<>();

  /**
   * Guards publishing the recording on the scheduler thread against a stop taking it away, since a
   * stop only waits a bounded time for that thread.
   */
  private final Object recordingLock = new Object();

  /**
   * Counted down once the recording that the scheduler thread creates is published or, if the
   * system was stopped meanwhile, stopped, so that a stop can wait for the latter without holding
   * {@link #recordingLock}. Guarded by {@link #recordingLock}.
   */
  private CountDownLatch recordingCreation;

  /**
   * Constructor.
   *
   * @param controllerFactory creates the implementation specific controller of profiling machinery
   * @param dataListener the listener for data being produced
   * @param startupDelay delay before starting jfr
   * @param startupDelayRandomRange randomization range for startup delay
   * @param uploadPeriod how often to upload data
   * @param isStartingFirst starting profiling before other tools
   * @throws ConfigurationException if the configuration information was bad.
   */
  public ProfilingSystem(
      final ConfigProvider configProvider,
      final ControllerFactory controllerFactory,
      final RecordingDataListener dataListener,
      final Duration startupDelay,
      final Duration startupDelayRandomRange,
      final Duration uploadPeriod,
      final boolean isStartingFirst)
      throws ConfigurationException {
    this(
        configProvider,
        controllerFactory,
        dataListener,
        startupDelay,
        startupDelayRandomRange,
        uploadPeriod,
        isStartingFirst,
        () -> new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER),
        ThreadLocalRandom.current());
  }

  ProfilingSystem(
      final ConfigProvider configProvider,
      final ControllerFactory controllerFactory,
      final RecordingDataListener dataListener,
      final Duration baseStartupDelay,
      final Duration startupDelayRandomRange,
      final Duration uploadPeriod,
      final boolean isStartingFirst,
      final Supplier<AgentTaskScheduler> schedulerFactory,
      final ThreadLocalRandom threadLocalRandom)
      throws ConfigurationException {
    this.configProvider = configProvider;
    this.controllerFactory = controllerFactory;
    this.dataListener = dataListener;
    this.uploadPeriod = uploadPeriod;
    this.isStartingFirst = isStartingFirst;
    this.schedulerFactory = schedulerFactory;

    if (baseStartupDelay.isNegative()) {
      throw new ConfigurationException("Startup delay must not be negative.");
    }

    if (startupDelayRandomRange.isNegative()) {
      throw new ConfigurationException("Startup delay random range must not be negative.");
    }

    if (uploadPeriod.isNegative() || uploadPeriod.isZero()) {
      throw new ConfigurationException("Upload period must be positive.");
    }

    // Note: it is important to not keep reference to the threadLocalRandom beyond the
    // constructor
    // since it is expected to be thread local.
    startupDelay = randomizeDuration(threadLocalRandom, baseStartupDelay, startupDelayRandomRange);
    startupDeadlineNanos = System.nanoTime() + startupDelay.toNanos();
  }

  /**
   * Creates the profiler(s) without starting them. Calling it again after a successful init is a
   * no-op.
   *
   * @throws UnsupportedEnvironmentException if no profiler is supported in this environment
   */
  public void init() throws UnsupportedEnvironmentException {
    lock.lock();
    try {
      if (controller != null) {
        return;
      }
      final ControllerContext controllerContext = new ControllerContext();
      controller = controllerFactory.create(controllerContext);
      context = controllerContext.snapshot();
      log.debug("Profiling system initialized");
    } finally {
      lock.unlock();
    }
  }

  /**
   * Starts recording and the periodic snapshots, initializing the profiler(s) first if needed.
   * Calling it while already started is a no-op.
   *
   * @throws UnsupportedEnvironmentException if no profiler is supported in this environment
   */
  public void start() throws UnsupportedEnvironmentException {
    start(false);
  }

  /**
   * Starts recording and the periodic snapshots, initializing the profiler(s) first if needed.
   * Calling it while already started is a no-op.
   *
   * @param immediately whether to start recording right away, instead of once the startup delay,
   *     counted from the creation of the system, is over, unless starting first; only safe once
   *     'main' has started
   * @throws UnsupportedEnvironmentException if no profiler is supported in this environment
   */
  public void start(boolean immediately) throws UnsupportedEnvironmentException {
    lock.lock();
    try {
      if (closed) {
        log.debug("Profiling system is shut down, not starting it");
        return;
      }
      if (scheduler != null) {
        log.debug("Profiling system is already started");
        return;
      }
      init();
      startFailed = false;

      final AgentTaskScheduler scheduler = schedulerFactory.get();
      this.scheduler = scheduler;
      log.debug(
          "Starting profiling system: startupDelay={}ms, uploadPeriod={}ms, isStartingFirst={}, immediately={}",
          startupDelay.toMillis(),
          uploadPeriod.toMillis(),
          isStartingFirst,
          immediately);

      if (immediately || isStartingFirst) {
        try {
          startProfilingRecording(scheduler);
        } catch (RuntimeException e) {
          this.scheduler = null;
          scheduler.shutdown(terminationTimeoutMillis, TimeUnit.MILLISECONDS);
          throw e;
        }
      } else {
        // Delay JFR initialization. This code is run from 'premain' and there is a known bug in JVM
        // which makes it crash if JFR is run before 'main' starts.
        // See https://bugs.openjdk.java.net/browse/JDK-8227011 and
        // https://bugs.openjdk.java.net/browse/JDK-8233197.
        scheduler.schedule(
            system -> system.startProfilingRecording(scheduler),
            this,
            Math.max(0, startupDeadlineNanos - System.nanoTime()),
            TimeUnit.NANOSECONDS);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Runs on the scheduler thread, so it must not take {@link #lock}: a stop holds it while joining
   * that thread.
   */
  private void startProfilingRecording(final AgentTaskScheduler scheduler) {
    final CountDownLatch creation = new CountDownLatch(1);
    synchronized (recordingLock) {
      if (scheduler.isShutdown()) {
        // stopped before the recording was created
        return;
      }
      recordingCreation = creation;
    }
    try {
      final Instant now = Instant.now();
      final OngoingRecording recording = controller.createRecording(RECORDING_NAME, context);
      final boolean published;
      synchronized (recordingLock) {
        // stop() marks the scheduler as shut down before it takes the recording, under this lock,
        // so a recording is either published before stop() takes it, or stopped below
        published = !scheduler.isShutdown();
        if (published) {
          this.recording = recording;
          scheduler.scheduleAtFixedRate(
              SnapshotRecording::snapshot,
              snapshotRecording = createSnapshotRecording(recording, now),
              uploadPeriod.toMillis(),
              uploadPeriod.toMillis(),
              TimeUnit.MILLISECONDS);
          started = true;
        }
      }
      if (!published) {
        // stopped while the recording was being created. Stopping the profiler(s) can take a
        // while, so it is done outside the lock that stop() takes; stop() waits for it through
        // recordingCreation instead, since a restart can only start once it is stopped
        stopRecording(recording);
      }
    } catch (UnsupportedEnvironmentException unsupported) {
      startFailed = !scheduler.isShutdown();
      ProfilerFlareLogger.getInstance()
          .log(
              "Datadog Profiling was enabled on an unsupported JVM, will not profile application. "
                  + "(OS: {}, JVM: lang={}, runtime={}, vendor={}) See {} for more details about supported JVMs.",
              isLinux() ? "Linux" : isWindows() ? "Windows" : isMacOs() ? "MacOS" : "Other",
              JavaVirtualMachine.getLangVersion(),
              JavaVirtualMachine.getRuntimeVersion(),
              JavaVirtualMachine.getRuntimeVendor(),
              "https://docs.datadoghq.com/profiler/enabling/java/?tab=commandarguments#requirements",
              unsupported);
    } catch (Throwable t) {
      startFailed = !scheduler.isShutdown();
      if (t instanceof RuntimeException) {
        // Possibly a wrapped exception related to Oracle JDK 8 JFR MX beans
        Throwable inspecting = t.getCause();
        while (inspecting != null) {
          String msg = inspecting.getMessage();
          if (msg != null && msg.contains("com.oracle.jrockit:type=FlightRecorder")) {
            // Yes, the commercial JFR is not enabled
            String logMsg =
                "You're running Oracle JDK 8. Datadog Continuous Profiler for Java depends on Java Flight Recorder, which requires a paid license in Oracle JDK 8. If you have one, please add the following `java` command line args: ‘-XX:+UnlockCommercialFeatures -XX:+FlightRecorder’. Alternatively, you can use a different Java 8 distribution like OpenJDK, where Java Flight Recorder is free.";
            ProfilerFlareLogger.getInstance().log(logMsg, t);
            log.warn(logMsg);
            // Do not log the underlying exception
            t = null;
            break;
          }
          inspecting = inspecting.getCause();
        }
      }
      if (t != null) {
        if (t instanceof IllegalStateException && "Shutdown in progress".equals(t.getMessage())) {
          ProfilerFlareLogger.getInstance().log("Shutdown in progress, cannot start profiling");
        } else {
          ProfilerFlareLogger.getInstance().log("Failed to start profiling", t);

          throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
      }
    } finally {
      creation.countDown();
    }
  }

  // Used for mocking
  SnapshotRecording createSnapshotRecording(OngoingRecording recording, Instant now) {
    return new SnapshotRecording(recording, now);
  }

  void stop() {
    stop(true);
  }

  /**
   * Stops recording and tears down the snapshot scheduler. The profiler(s) stay initialized, so the
   * system can be started again. Calling it while not started is a no-op.
   *
   * @param snapshot whether to take a last snapshot before stopping
   */
  public void stop(boolean snapshot) {
    countedStop(snapshot, true);
  }

  /**
   * Stops under {@link #lock}, counted in {@link #pendingStops} before taking it, so that a
   * shutdown without snapshot that finds the lock taken knows that it is a stop, see {@link
   * #shutdown}.
   */
  private void countedStop(boolean snapshot, boolean restartable) {
    pendingStops.incrementAndGet();
    try {
      lock.lock();
      try {
        stop(snapshot, restartable);
      } finally {
        lock.unlock();
      }
    } finally {
      pendingStops.decrementAndGet();
    }
  }

  /**
   * Stops recording like {@link #stop(boolean)}, and makes the system final: it can't be started
   * again. Unlike {@code stop}, it only closes the recording, which is all that a system that won't
   * be restarted needs, and is cheaper in a shutdown hook.
   *
   * <p>If a stop is already in progress, e.g. one triggered by remote config that is uploading its
   * final snapshot, a shutdown with a snapshot waits for it, so that its upload is not cut off,
   * which takes at most as long as the final upload of a shutdown with a snapshot. A shutdown
   * without one, as from a shutdown hook, doesn't upload anything, so it returns right away
   * instead.
   *
   * @param snapshot whether to take a last snapshot before stopping
   */
  public void shutdown(boolean snapshot) {
    // set before taking the lock, so that a start racing with the in-progress stop is refused
    closed = true;
    if (snapshot) {
      // waits for a stop in progress, and is waited for by a shutdown without snapshot
      countedStop(true, false);
      return;
    }
    if (!lock.tryLock()) {
      // a stop is counted before it takes the lock, so this can't miss one that holds it
      if (pendingStops.get() > 0) {
        log.debug("Profiling system is already stopping, not waiting for it to shut down");
        return;
      }
      // held for something short, e.g. checking whether it is running: the recording still has
      // to be closed
      lock.lock();
    }
    try {
      stop(false, false);
    } finally {
      lock.unlock();
    }
  }

  private void stop(boolean snapshot, boolean restartable) {
    startFailed = false;
    final AgentTaskScheduler scheduler = this.scheduler;
    if (scheduler == null) {
      return;
    }
    this.scheduler = null;
    scheduler.shutdown(terminationTimeoutMillis, TimeUnit.MILLISECONDS);

    // the scheduler is shut down, so after this the scheduler thread can't publish a recording,
    // even if it didn't terminate in time
    final SnapshotRecording snapshotRecording;
    final CountDownLatch creation;
    synchronized (recordingLock) {
      snapshotRecording = this.snapshotRecording;
      this.snapshotRecording = null;
      creation = recordingCreation;
      recordingCreation = null;
    }
    // a recording that the scheduler thread was still creating is stopped by that thread, once
    // created; wait for it, without holding the lock, so that a restart can start a new one
    awaitRecordingCreation(creation);
    if (snapshot && snapshotRecording != null && !scheduler.isTerminated()) {
      // the scheduler thread is still taking a periodic snapshot, e.g. a slow upload: taking
      // another one at the same time would upload the same data twice
      log.debug("Profiling scheduler did not terminate in time, skipping the final snapshot");
    } else if (snapshot && snapshotRecording != null) {
      // reads this.recording, so it is cleared only after the snapshot. It is uploaded
      // synchronously either way, but only tagged as a shutdown one when the JVM is going away,
      // not for a restart
      snapshotRecording.snapshot(
          restartable ? ProfilingSnapshot.Kind.PERIODIC : ProfilingSnapshot.Kind.ON_SHUTDOWN, true);
    }

    final OngoingRecording recording = this.recording;
    started = false;
    if (recording == null) {
      return;
    }
    if (snapshotLock.tryLock()) {
      try {
        // later snapshots skip it
        this.recording = null;
      } finally {
        snapshotLock.unlock();
      }
      if (restartable) {
        stopRecording(recording);
      } else {
        recording.close();
      }
    } else {
      // a scheduler thread that didn't terminate in time is snapshotting it: it must not be
      // stopped meanwhile, nor waited for without bound, so the thread that gets the lock next,
      // possibly this one, stops it
      this.recording = null;
      log.debug("Profiling recording is being snapshot, stopping it once the snapshot is done");
      abandonedRecording.set(recording);
      stopAbandonedRecording();
    }
  }

  /**
   * Stops the abandoned recording, if any, unless a snapshot is in progress, which then does it
   * once it's done: every holder of {@link #snapshotLock} calls this after releasing it, so the
   * last one to release it after a recording is abandoned stops it.
   */
  private void stopAbandonedRecording() {
    if (abandonedRecording.get() != null && snapshotLock.tryLock()) {
      try {
        final OngoingRecording recording = abandonedRecording.getAndSet(null);
        if (recording != null) {
          stopRecording(recording);
        }
      } finally {
        snapshotLock.unlock();
      }
    }
  }

  private void awaitRecordingCreation(final CountDownLatch creation) {
    if (creation == null) {
      return;
    }
    try {
      if (!creation.await(terminationTimeoutMillis, TimeUnit.MILLISECONDS)) {
        log.debug(
            "Profiling recording still being created or stopped after {}ms, not waiting for it",
            terminationTimeoutMillis);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void stopRecording(final OngoingRecording recording) {
    try {
      // stopping, and not only closing, is what allows a new recording to be started later
      final RecordingData data = recording.stop();
      if (data != null) {
        data.release();
      }
    } catch (final Throwable t) {
      log.debug("Failed to stop profiling recording", t);
    } finally {
      recording.close();
    }
  }

  /**
   * Applies a new configuration to the profiler(s), used from the next {@link #start()}. The
   * profiler(s) are initialized first if needed.
   *
   * <p>Only the profilers whose controller supports it take the new configuration, which is the
   * Datadog profiler for now: the JFR recording keeps its startup settings, except for the events
   * that the Datadog profiler takes over, as told by the refreshed context.
   *
   * @param configProvider the new configuration
   * @param modeOverrides the Datadog profiler modes to switch on ({@code true}) or off ({@code
   *     false}) over what the configuration enables, e.g. by remote config
   * @throws UnsupportedEnvironmentException if no profiler is supported in this environment
   * @throws IllegalStateException if the system is running
   */
  public void reconfigure(ConfigProvider configProvider, Map<ProfilingMode, Boolean> modeOverrides)
      throws UnsupportedEnvironmentException {
    lock.lock();
    try {
      if (scheduler != null) {
        throw new IllegalStateException("Profiling system can't be reconfigured while running");
      }
      init();
      controller.reconfigure(configProvider, modeOverrides);
      // refresh the context, e.g. so JFR knows which events the Datadog profiler now takes over
      final ControllerContext controllerContext = new ControllerContext();
      controllerContext.setDatadogProfilerUnavailableReason(
          context.getDatadogProfilerUnavailableReason());
      controller.configure(controllerContext);
      context = controllerContext.snapshot();
      log.debug("Profiling system reconfigured");
    } finally {
      lock.unlock();
    }
  }

  /** Whether the system was started and not stopped since, even if not recording yet. */
  public boolean isRunning() {
    lock.lock();
    try {
      return scheduler != null;
    } finally {
      lock.unlock();
    }
  }

  /** Whether the startup delay, counted from the creation of the system, is over. */
  public boolean isPastStartupDelay() {
    return System.nanoTime() - startupDeadlineNanos >= 0;
  }

  /** Whether a recording is in progress. */
  public boolean isStarted() {
    return started;
  }

  /**
   * Whether the last start didn't give a recording, e.g. because it was delayed and then failed.
   * Cleared by the next {@link #start} or {@link #stop}.
   */
  public boolean isStartFailed() {
    return startFailed;
  }

  @VisibleForTesting
  final Duration getStartupDelay() {
    return startupDelay;
  }

  private static Duration randomizeDuration(
      final ThreadLocalRandom random, final Duration duration, final Duration range) {
    return duration.plus(Duration.ofMillis(random.nextLong(range.toMillis())));
  }

  /**
   * Takes the snapshots of one recording, the one it was created for: its {@link #lastSnapshot}
   * only makes sense for that recording, so it never snapshots another one, e.g. the recording of a
   * restart, when its scheduler thread outlives a stop.
   */
  final class SnapshotRecording {
    private final Duration ONE_NANO = Duration.ofNanos(1);

    private final OngoingRecording recording;

    private Instant lastSnapshot;

    SnapshotRecording(final OngoingRecording recording, final Instant startTime) {
      this.recording = recording;
      lastSnapshot = startTime;
    }

    public void snapshot() {
      snapshot(ProfilingSnapshot.Kind.PERIODIC, false);
    }

    /**
     * @param kind the kind of snapshot, as reported with the profile
     * @param sync whether to wait for the profile to be handled, e.g. uploaded
     */
    public void snapshot(ProfilingSnapshot.Kind kind, boolean sync) {
      final RecordingType recordingType = RecordingType.CONTINUOUS;
      try {
        final RecordingData recordingData;
        // a stop doesn't stop the recording while this holds the lock
        snapshotLock.lock();
        try {
          // cleared by stop(), which a scheduler thread that didn't terminate in time can outlive,
          // and replaced by a restart after it
          if (ProfilingSystem.this.recording != recording) {
            log.debug("Profiling recording is stopped, skipping the snapshot");
            return;
          }
          log.debug("Creating profiler snapshot");
          recordingData = recording.snapshot(lastSnapshot, kind);
        } finally {
          snapshotLock.unlock();
          // a stop may have abandoned the recording while it was being snapshot
          stopAbandonedRecording();
        }
        log.debug("Snapshot created: {}", recordingData);
        if (recordingData != null) {
          // To make sure that we don't get data twice, we say that the next start should be
          // the last recording end time plus one nano second. The reason for this is that when
          // JFR is filtering the stream it will only discard earlier chunks that have an end
          // time that is before (not before or equal to) the requested start time of the filter.
          lastSnapshot = recordingData.getEnd().plus(ONE_NANO);
          dataListener.onNewData(recordingType, recordingData, sync);
        } else {
          lastSnapshot = Instant.now();
        }
      } catch (final Exception e) {
        log.error(SEND_TELEMETRY, "Exception in profiling thread, continuing", e);
      } catch (final Throwable t) {
        /*
        Try to continue even after fatal exception. It seems to be useful to attempt to store profile when this happens.
        For example JVM maybe out of heap and throwing OutOfMemoryError - we probably still would want to continue and
        try to save profile later.
        Another reason is that it may be bad to stop profiling if the rest of the app is continuing.
         */
        try {
          log.error(SEND_TELEMETRY, "Fatal exception in profiling thread, trying to continue", t);
        } catch (final Throwable t2) {
          // This should almost never happen and there is not much we can do here in cases like
          // OutOfMemoryError, so we will just ignore this.
        }
      }
    }
  }
}
