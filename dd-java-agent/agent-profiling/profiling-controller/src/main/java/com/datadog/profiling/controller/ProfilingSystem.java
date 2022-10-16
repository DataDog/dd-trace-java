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

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROFILER_RECORDING_SCHEDULER;

import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.util.AgentTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sets up the profiling strategy and schedules the profiling recordings. */
public final class ProfilingSystem {
  private static final Logger log = LoggerFactory.getLogger(ProfilingSystem.class);
  static final String RECORDING_NAME = "dd-profiling";

  private static final long TERMINATION_TIMEOUT = 10;

  private final AgentTaskScheduler scheduler;
  private final ProfilingSystemConfig config;
  private final Controller controller;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;

  private final Duration startupDelay;

  private OngoingRecording recording;
  private SnapshotRecording snapshotRecording;
  private volatile boolean started = false;

  /**
   * Constructor.
   *
   * @param controller implementation specific controller of profiling machinery
   * @param dataListener the listener for data being produced
   * @throws ConfigurationException if the configuration information was bad.
   */
  public ProfilingSystem(
      final ProfilingSystemConfig config,
      final Controller controller,
      final RecordingDataListener dataListener)
      throws ConfigurationException {
    this(
        config,
        controller,
        dataListener,
        new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER),
        ThreadLocalRandom.current());
  }

  ProfilingSystem(
      final ProfilingSystemConfig config,
      final Controller controller,
      final RecordingDataListener dataListener,
      final AgentTaskScheduler scheduler,
      final ThreadLocalRandom threadLocalRandom)
      throws ConfigurationException {
    this.config = config;
    this.controller = controller;
    this.dataListener = dataListener;
    this.scheduler = scheduler;

    // Note: it is important to not keep reference to the threadLocalRandom beyond the
    // constructor
    // since it is expected to be thread local.
    startupDelay = randomizeDuration(threadLocalRandom, config.getStartupDelay(), config.getSnapshotInterval());
  }

  public void start() {
    log.debug(
        "Starting profiling system: startupDelay={}ms, snapshotInterval={}ms, profileStartup={}",
        startupDelay.toMillis(),
        config.getSnapshotInterval().toMillis(),
        config.isProfilingStartup());

    boolean shouldProfileStartup = config.isProfilingStartup() && controller.isForceStartFirstSupported();
    Runnable task = this::startProfilingRecording;
    if (shouldProfileStartup) {
      task.run();
    } else {
      scheduler.schedule(
          task,
          startupDelay.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  private void startProfilingRecording() {
    try {
      final Instant now = Instant.now();
      recording = controller.createRecording(RECORDING_NAME);
      scheduler.scheduleAtFixedRate(
          SnapshotRecording::snapshot,
          snapshotRecording = createSnapshotRecording(now),
          config.getSnapshotInterval().toMillis(),
          config.getSnapshotInterval().toMillis(),
          TimeUnit.MILLISECONDS);
      started = true;
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        // Possibly a wrapped exception related to Oracle JDK 8 JFR MX beans
        Throwable inspecting = t.getCause();
        while (inspecting != null) {
          String msg = inspecting.getMessage();
          if (msg != null && msg.contains("com.oracle.jrockit:type=FlightRecorder")) {
            // Yes, the commercial JFR is not enabled
            log.warn(
                "Oracle JDK 8 is being used, where the Flight Recorder is a commercial feature. Please, make sure you have a valid license to use Flight Recorder  (for example Oracle Java SE Advanced) and then add ‘-XX:+UnlockCommercialFeatures -XX:+FlightRecorder’ to your launcher script. Alternatively, use an OpenJDK 8 distribution from another vendor, where the Flight Recorder is free.");
            // Do not log the underlying exception
            t = null;
            break;
          }
          inspecting = inspecting.getCause();
        }
      }
      if (t != null) {
        if (t instanceof IllegalStateException && "Shutdown in progress".equals(t.getMessage())) {
          log.debug("Shutdown in progress, cannot start profiling");
        } else {
          log.error("Fatal exception during profiling startup", t);
          throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
      }
    }
  }

  // Used for mocking
  SnapshotRecording createSnapshotRecording(Instant now) {
    return new SnapshotRecording(now);
  }

  void shutdown() {
    shutdown(false);
  }

  /** Shuts down the profiling system. */
  public final void shutdown(boolean snapshot) {
    scheduler.shutdown(TERMINATION_TIMEOUT, TimeUnit.SECONDS);

    if (snapshotRecording != null) {
      if (snapshot) {
        snapshotRecording.snapshot(true);
      }
      snapshotRecording = null;
    }

    // Here we assume that all other threads have been shutdown and we can close running
    // recording
    if (recording != null) {
      recording.close();
    }

    started = false;
  }

  public boolean isStarted() {
    return started;
  }

  /** VisibleForTesting */
  final Duration getStartupDelay() {
    return startupDelay;
  }

  private static Duration randomizeDuration(
      final ThreadLocalRandom random, final Duration base, final Duration range) {
    return base.plus(Duration.ofMillis(random.nextLong(range.toMillis())));
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
        log.error("Exception in profiling thread, continuing", e);
      } catch (final Throwable t) {
        /*
        Try to continue even after fatal exception. It seems to be useful to attempt to store profile when this happens.
        For example JVM maybe out of heap and throwing OutOfMemoryError - we probably still would want to continue and
        try to save profile later.
        Another reason is that it may be bad to stop profiling if the rest of the app is continuing.
         */
        try {
          log.error("Fatal exception in profiling thread, trying to continue", t);
        } catch (final Throwable t2) {
          // This should almost never happen and there is not much we can do here in cases like
          // OutOfMemoryError, so we will just ignore this.
        }
      }
    }
  }
}
