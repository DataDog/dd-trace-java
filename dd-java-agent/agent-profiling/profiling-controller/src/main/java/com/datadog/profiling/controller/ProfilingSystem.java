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
  private final Controller controller;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;

  private final Duration startupDelay;
  private final Duration uploadPeriod;
  private final boolean isStartingFirst;

  private OngoingRecording recording;
  private volatile boolean started = false;

  /**
   * Constructor.
   *
   * @param controller implementation specific controller of profiling machinery
   * @param dataListener the listener for data being produced
   * @param startupDelay delay before starting jfr
   * @param startupDelayRandomRange randomization range for startup delay
   * @param uploadPeriod how often to upload data
   * @param isStartingFirst starting profiling before other tools
   * @throws ConfigurationException if the configuration information was bad.
   */
  public ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration startupDelay,
      final Duration startupDelayRandomRange,
      final Duration uploadPeriod,
      final boolean isStartingFirst)
      throws ConfigurationException {
    this(
        controller,
        dataListener,
        startupDelay,
        startupDelayRandomRange,
        uploadPeriod,
        isStartingFirst,
        new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER),
        ThreadLocalRandom.current());
  }

  ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration baseStartupDelay,
      final Duration startupDelayRandomRange,
      final Duration uploadPeriod,
      final boolean isStartingFirst,
      final AgentTaskScheduler scheduler,
      final ThreadLocalRandom threadLocalRandom)
      throws ConfigurationException {
    this.controller = controller;
    this.dataListener = dataListener;
    this.uploadPeriod = uploadPeriod;
    this.isStartingFirst = isStartingFirst;
    this.scheduler = scheduler;

    if (baseStartupDelay.isNegative()) {
      throw new ConfigurationException("Startup delay must not be negative.");
    }

    if (startupDelayRandomRange.isNegative()) {
      throw new ConfigurationException("Startup delay random range must not be negative.");
    }

    if (uploadPeriod.isNegative() || uploadPeriod.isZero()) {
      throw new ConfigurationException("Upload period must be positive.");
    }

    // Note: is is important to not keep reference to the threadLocalRandom beyond the constructor
    // since it is expected to be thread local.
    startupDelay = randomizeDuration(threadLocalRandom, baseStartupDelay, startupDelayRandomRange);
  }

  public final void start() {
    log.debug(
        "Starting profiling system: startupDelay={}ms, uploadPeriod={}ms, isStartingFirst={}",
        startupDelay.toMillis(),
        uploadPeriod.toMillis(),
        isStartingFirst);

    if (isStartingFirst) {
      startProfilingRecording();
    } else {
      // Delay JFR initialization. This code is run from 'premain' and there is a known bug in JVM
      // which makes it crash if JFR is run before 'main' starts.
      // See https://bugs.openjdk.java.net/browse/JDK-8227011 and
      // https://bugs.openjdk.java.net/browse/JDK-8233197.
      scheduler.schedule(
          ProfilingSystem::startProfilingRecording,
          this,
          startupDelay.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  private void startProfilingRecording() {
    try {
      final Instant now = Instant.now();
      log.debug("Initiating profiling recording");
      recording = controller.createRecording(RECORDING_NAME);
      scheduler.scheduleAtFixedRate(
          SnapshotRecording::snapshot,
          new SnapshotRecording(now),
          uploadPeriod.toMillis(),
          uploadPeriod.toMillis(),
          TimeUnit.MILLISECONDS);
      started = true;
    } catch (final Throwable t) {
      if (t instanceof IllegalStateException && "Shutdown in progress".equals(t.getMessage())) {
        log.debug("Shutdown in progress, cannot start profiling");
      } else {
        log.error("Fatal exception during profiling startup", t);
        throw t;
      }
    }
  }

  /** Shuts down the profiling system. */
  public final void shutdown() {
    scheduler.shutdown(TERMINATION_TIMEOUT, TimeUnit.SECONDS);

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
      final ThreadLocalRandom random, final Duration duration, final Duration range) {
    return duration.plus(Duration.ofMillis(random.nextLong(range.toMillis())));
  }

  private final class SnapshotRecording {

    private Instant lastSnapshot;

    SnapshotRecording(final Instant startTime) {
      lastSnapshot = startTime;
    }

    public void snapshot() {
      final RecordingType recordingType = RecordingType.CONTINUOUS;
      try {
        log.debug("Creating profiler snapshot");
        final RecordingData recordingData = recording.snapshot(lastSnapshot, Instant.now());
        // The hope here is that we do not get chunk rotated after taking snapshot and before we
        // take this timestamp otherwise we will start losing data.
        lastSnapshot = Instant.now();
        if (recordingData != null) {
          dataListener.onNewData(recordingType, recordingData);
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
