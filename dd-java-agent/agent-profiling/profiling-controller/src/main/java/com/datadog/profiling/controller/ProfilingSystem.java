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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/** Sets up the profiling strategy and schedules the profiling recordings. */
@Slf4j
public final class ProfilingSystem {
  static final String RECORDING_NAME = "dd-profiling";

  private static final long TERMINATION_TIMEOUT = 10;

  private final ScheduledExecutorService executorService;
  private final Controller controller;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;

  private final Duration startupDelay;
  private final Duration uploadPeriod;
  private final int continuousToPeriodicUploadsRatio;
  private final int periodicRecordingCounterStart;

  private OngoingRecording continuousRecording;
  private final AtomicReference<OngoingRecording> periodicRecordingRef =
      new AtomicReference<>(null);
  private boolean started = false;

  /**
   * Constructor.
   *
   * @param controller implementation specific controller of profiling machinery
   * @param dataListener the listener for data being produced
   * @param startupDelay delay before starting jfr
   * @param startupDelayRandomRange randomization range for startup delay
   * @param uploadPeriod how often to upload data
   * @param continuousToPeriodicUploadsRatio how often to run and upload periodic recordings, given
   *     in units of {@code uploadPeriod}
   * @throws ConfigurationException if the configuration information was bad.
   */
  public ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration startupDelay,
      final Duration startupDelayRandomRange,
      final Duration uploadPeriod,
      final int continuousToPeriodicUploadsRatio)
      throws ConfigurationException {
    this(
        controller,
        dataListener,
        startupDelay,
        startupDelayRandomRange,
        uploadPeriod,
        continuousToPeriodicUploadsRatio,
        Executors.newScheduledThreadPool(1, new ProfilingThreadFactory()),
        ThreadLocalRandom.current());
  }

  ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration baseStartupDelay,
      final Duration startupDelayRandomRange,
      final Duration uploadPeriod,
      final int continuousToPeriodicUploadsRatio,
      final ScheduledExecutorService executorService,
      final ThreadLocalRandom threadLocalRandom)
      throws ConfigurationException {
    this.controller = controller;
    this.dataListener = dataListener;
    this.uploadPeriod = uploadPeriod;
    this.executorService = executorService;
    this.continuousToPeriodicUploadsRatio = continuousToPeriodicUploadsRatio;

    if (baseStartupDelay.isNegative()) {
      throw new ConfigurationException("Startup delay must not be negative.");
    }

    if (startupDelayRandomRange.isNegative()) {
      throw new ConfigurationException("Startup delay random range must not be negative.");
    }

    if (uploadPeriod.isNegative() || uploadPeriod.isZero()) {
      throw new ConfigurationException("Upload period must be positive.");
    }

    if (continuousToPeriodicUploadsRatio < 0) {
      throw new ConfigurationException(
          "Continuous to periodic uploads ratio must not be negative.");
    }

    // Note: is is important to not keep reference to the threadLocalRandom beyond the constructor
    // since it is expected to be thread local.
    startupDelay = randomizeDuration(threadLocalRandom, baseStartupDelay, startupDelayRandomRange);

    if (continuousToPeriodicUploadsRatio > 1) {
      // Lower value for periodicRecordingCounterStart 1 to account for non periodic recording that
      // is already running.
      periodicRecordingCounterStart =
          threadLocalRandom.nextInt(1, continuousToPeriodicUploadsRatio);
    } else {
      periodicRecordingCounterStart = 1;
    }
  }

  public final void start() {
    log.info(
        "Starting profiling system: startupDelay = {}ms, uploadPeriod = {}ms, continuousToPeriodicUploadsRation = {}",
        startupDelay.toMillis(),
        uploadPeriod.toMillis(),
        continuousToPeriodicUploadsRatio);

    // Delay JFR initialization. This code is run from 'premain' and there is a known bug in JVM
    // which makes it crash if JFR is run before 'main' starts.
    // See https://bugs.openjdk.java.net/browse/JDK-8227011
    executorService.schedule(
        () -> {
          final Instant now = Instant.now();
          continuousRecording = controller.createContinuousRecording(RECORDING_NAME);

          if (continuousToPeriodicUploadsRatio == 1) {
            periodicRecordingRef.set(controller.createPeriodicRecording(RECORDING_NAME));
          }

          executorService.scheduleAtFixedRate(
              new SnapshotRecording(periodicRecordingCounterStart, now),
              uploadPeriod.toMillis(),
              uploadPeriod.toMillis(),
              TimeUnit.MILLISECONDS);
          started = true;
        },
        startupDelay.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /** Shuts down the profiling system. */
  public final void shutdown() {
    executorService.shutdownNow();

    try {
      executorService.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      log.error("Wait for executor shutdown interrupted");
    }

    // Here we assume that all other threads have been shutdown and we can close remaining
    // recordings
    if (continuousRecording != null) {
      continuousRecording.close();
    }

    final OngoingRecording recording = periodicRecordingRef.getAndSet(null);
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

  private final class SnapshotRecording implements Runnable {

    private Instant lastSnapshot;
    // 1 to account for non periodic recording that is already running
    private int periodicRecordingCounter = 1;

    SnapshotRecording(final int periodicRecordingCounterStart, final Instant startTime) {
      periodicRecordingCounter = periodicRecordingCounterStart;
      lastSnapshot = startTime;
    }

    @Override
    public void run() {
      RecordingType recordingType = RecordingType.CONTINUOUS;

      if (continuousToPeriodicUploadsRatio == 1) {
        // Periodic recording is already running continuously
        recordingType = RecordingType.PERIODIC;
      } else if (continuousToPeriodicUploadsRatio > 1) {
        final OngoingRecording periodicRecording = periodicRecordingRef.getAndSet(null);
        if (periodicRecording != null) {
          // Just stop the recording - we will get the data in snapshot
          periodicRecording.close();
          recordingType = RecordingType.PERIODIC;
        }
      }

      try {
        final RecordingData recording = continuousRecording.snapshot(lastSnapshot, Instant.now());
        // The hope here is that we do not get chunk rotated after taking snapshot and before we
        // take this timestamp otherwise we will start losing data.
        lastSnapshot = Instant.now();
        if (recording != null) {
          dataListener.onNewData(recordingType, recording);
        }
      } catch (final Exception e) {
        log.error("Cannot upload snapshot", e);
      }

      if (continuousToPeriodicUploadsRatio > 1) {
        periodicRecordingCounter++;
        if (periodicRecordingCounter >= continuousToPeriodicUploadsRatio) {
          periodicRecordingCounter = 0;
          final OngoingRecording newRecording = controller.createPeriodicRecording(RECORDING_NAME);
          if (!periodicRecordingRef.compareAndSet(null, newRecording)) {
            newRecording.close(); // should never happen
          }
        }
      }
    }
  }

  private static final class ProfilingThreadFactory implements ThreadFactory {
    private static final ThreadGroup THREAD_GROUP = new ThreadGroup("Datadog Profiler");

    @Override
    public Thread newThread(final Runnable r) {
      final Thread t = new Thread(THREAD_GROUP, r, "dd-profiler-recording-scheduler");
      t.setDaemon(true);
      return t;
    }
  }
}
