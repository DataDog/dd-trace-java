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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets up the profiling strategy and schedules the profiling recordings.
 *
 * <p>Lifecycle: {@link #init()} creates the profiler(s) without starting them, {@link #start()}
 * starts recording and the snapshot scheduler, and {@link #stop(boolean)} stops both. The system
 * can be started again after it was stopped.
 */
public final class ProfilingSystem {
  private static final Logger log = LoggerFactory.getLogger(ProfilingSystem.class);
  static final String RECORDING_NAME = "dd-profiling";

  private static final long TERMINATION_TIMEOUT = 10;

  private final ConfigProvider configProvider;
  private final ControllerFactory controllerFactory;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;
  // a stopped scheduler can't be restarted, so each start gets a new one
  private final Supplier<AgentTaskScheduler> schedulerFactory;

  private final Duration startupDelay;
  private final Duration uploadPeriod;
  private final boolean isStartingFirst;

  // created by init() and kept across restarts
  private Controller controller;
  private ControllerContext.Snapshot context;

  // set by start() and cleared by stop()
  private AgentTaskScheduler scheduler;
  private volatile OngoingRecording recording;
  private volatile SnapshotRecording snapshotRecording;
  private volatile boolean started = false;

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
  }

  /**
   * Creates the profiler(s) without starting them. Calling it again after a successful init is a
   * no-op.
   *
   * @throws UnsupportedEnvironmentException if no profiler is supported in this environment
   */
  public synchronized void init() throws UnsupportedEnvironmentException {
    if (controller != null) {
      return;
    }
    final ControllerContext controllerContext = new ControllerContext();
    controller = controllerFactory.create(controllerContext);
    context = controllerContext.snapshot();
    log.debug("Profiling system initialized");
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
   * @param immediately whether to start recording right away, instead of after the startup delay
   *     unless starting first; only safe once 'main' has started
   * @throws UnsupportedEnvironmentException if no profiler is supported in this environment
   */
  public synchronized void start(boolean immediately) throws UnsupportedEnvironmentException {
    if (scheduler != null) {
      log.debug("Profiling system is already started");
      return;
    }
    init();

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
        scheduler.shutdown(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
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
          startupDelay.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  // runs on the scheduler thread, so it must not lock 'this': stop() holds it while joining that
  // thread
  private void startProfilingRecording(final AgentTaskScheduler scheduler) {
    try {
      final Instant now = Instant.now();
      final OngoingRecording recording = controller.createRecording(RECORDING_NAME, context);
      if (scheduler.isShutdown()) {
        // stopped while the recording was being created
        stopRecording(recording);
        return;
      }
      this.recording = recording;
      scheduler.scheduleAtFixedRate(
          SnapshotRecording::snapshot,
          snapshotRecording = createSnapshotRecording(now),
          uploadPeriod.toMillis(),
          uploadPeriod.toMillis(),
          TimeUnit.MILLISECONDS);
      started = true;
    } catch (UnsupportedEnvironmentException unsupported) {
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
    }
  }

  // Used for mocking
  SnapshotRecording createSnapshotRecording(Instant now) {
    return new SnapshotRecording(now);
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
  public synchronized void stop(boolean snapshot) {
    final AgentTaskScheduler scheduler = this.scheduler;
    if (scheduler == null) {
      return;
    }
    this.scheduler = null;
    scheduler.shutdown(TERMINATION_TIMEOUT, TimeUnit.SECONDS);

    // Here we assume that the scheduler thread has terminated and we can stop the running
    // recording
    final SnapshotRecording snapshotRecording = this.snapshotRecording;
    this.snapshotRecording = null;
    if (snapshot && snapshotRecording != null) {
      snapshotRecording.snapshot(true);
    }

    final OngoingRecording recording = this.recording;
    this.recording = null;
    if (recording != null) {
      stopRecording(recording);
    }

    started = false;
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
   * @param configProvider the new configuration
   * @throws UnsupportedEnvironmentException if no profiler is supported in this environment
   * @throws IllegalStateException if the system is running
   */
  public synchronized void reconfigure(ConfigProvider configProvider)
      throws UnsupportedEnvironmentException {
    if (scheduler != null) {
      throw new IllegalStateException("Profiling system can't be reconfigured while running");
    }
    init();
    controller.reconfigure(configProvider);
    // refresh the context, e.g. so JFR knows which events the Datadog profiler now takes over
    final ControllerContext controllerContext = new ControllerContext();
    controllerContext.setDatadogProfilerUnavailableReason(
        context.getDatadogProfilerUnavailableReason());
    controller.configure(controllerContext);
    context = controllerContext.snapshot();
    log.debug("Profiling system reconfigured");
  }

  /** Whether the system was started and not stopped since, even if not recording yet. */
  public synchronized boolean isRunning() {
    return scheduler != null;
  }

  /** Whether a recording is in progress. */
  public boolean isStarted() {
    return started;
  }

  @VisibleForTesting
  final Duration getStartupDelay() {
    return startupDelay;
  }

  private static Duration randomizeDuration(
      final ThreadLocalRandom random, final Duration duration, final Duration range) {
    return duration.plus(Duration.ofMillis(random.nextLong(range.toMillis())));
  }

  final class SnapshotRecording {
    private final Duration ONE_NANO = Duration.ofNanos(1);

    private Instant lastSnapshot;

    SnapshotRecording(final Instant startTime) {
      lastSnapshot = startTime;
    }

    public void snapshot() {
      snapshot(false);
    }

    public void snapshot(boolean onShutdown) {
      final RecordingType recordingType = RecordingType.CONTINUOUS;
      try {
        log.debug("Creating profiler snapshot");
        final RecordingData recordingData =
            recording.snapshot(
                lastSnapshot,
                onShutdown ? ProfilingSnapshot.Kind.ON_SHUTDOWN : ProfilingSnapshot.Kind.PERIODIC);
        log.debug("Snapshot created: {}", recordingData);
        if (recordingData != null) {
          // To make sure that we don't get data twice, we say that the next start should be
          // the last recording end time plus one nano second. The reason for this is that when
          // JFR is filtering the stream it will only discard earlier chunks that have an end
          // time that is before (not before or equal to) the requested start time of the filter.
          lastSnapshot = recordingData.getEnd().plus(ONE_NANO);
          dataListener.onNewData(recordingType, recordingData, onShutdown);
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
