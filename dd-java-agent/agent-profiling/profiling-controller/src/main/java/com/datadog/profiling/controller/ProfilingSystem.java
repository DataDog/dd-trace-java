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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/** Sets up the profiling strategy and schedules the profiling recordings. */
@Slf4j
public final class ProfilingSystem {
  static final String PERIODIC_RECORDING_NAME = "dd-periodic";
  static final String CONTINUOUS_RECORDING_NAME = "dd-continuous";

  private static final long TERMINATION_TIMEOUT = 10;

  private final ScheduledExecutorService executorService;
  private final Controller controller;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;

  private final Duration uploadPeriod;
  private final int continuousToPeriodicUploadsRatio;

  private OngoingRecording continuousRecording;
  private final AtomicReference<OngoingRecording> periodicRecordingRef =
      new AtomicReference<>(null);

  /**
   * Constructor.
   *
   * @param controller implementation specific controller of profiling machinery
   * @param dataListener the listener for data being produced.
   * @param uploadPeriod how often to upload data
   * @param continuousToPeriodicUploadsRatio how often to run and upload periodic recordings, given
   *     in units of {@code uploadPeriod}
   * @throws ConfigurationException if the configuration information was bad.
   */
  public ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration uploadPeriod,
      final int continuousToPeriodicUploadsRatio)
      throws ConfigurationException {
    this(
        controller,
        dataListener,
        uploadPeriod,
        continuousToPeriodicUploadsRatio,
        Executors.newScheduledThreadPool(1, new ProfilingThreadFactory()));
  }

  /** Constructor visible for testing. */
  ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration uploadPeriod,
      final int continuousToPeriodicUploadsRatio,
      final ScheduledExecutorService executorService)
      throws ConfigurationException {
    this.controller = controller;
    this.dataListener = dataListener;
    this.uploadPeriod = uploadPeriod;
    this.executorService = executorService;
    this.continuousToPeriodicUploadsRatio = continuousToPeriodicUploadsRatio;

    if (uploadPeriod.isNegative() || uploadPeriod.isZero()) {
      throw new ConfigurationException("Upload period must be positive.");
    }

    if (continuousToPeriodicUploadsRatio < 0) {
      throw new ConfigurationException(
          "Continuous to periodic uploads ratio must not be negative.");
    }
  }

  public final void start() {
    final Instant now = Instant.now();
    continuousRecording = controller.createContinuousRecording(CONTINUOUS_RECORDING_NAME);
    executorService.scheduleAtFixedRate(
        new SnapshotRecording(now),
        uploadPeriod.toMillis(),
        uploadPeriod.toMillis(),
        TimeUnit.MILLISECONDS);

    if (continuousToPeriodicUploadsRatio == 1) {
      periodicRecordingRef.set(controller.createPeriodicRecording(PERIODIC_RECORDING_NAME));
    }
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
  }

  private final class SnapshotRecording implements Runnable {

    private Instant lastSnapshot;
    // 1 to account for recording that is already running
    private int periodicRecordingCounter = 1;

    SnapshotRecording(final Instant startTime) {
      lastSnapshot = startTime;
    }

    @Override
    public void run() {
      final Instant now = Instant.now();
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
        final RecordingData recording = continuousRecording.snapshot(lastSnapshot, now);
        lastSnapshot = now;
        if (recording != null) {
          dataListener.onNewData(recordingType, recording);
        }
      } catch (final Exception e) {
        log.error("Cannot upload snapshot", e);
      }

      if (continuousToPeriodicUploadsRatio > 1) {
        periodicRecordingCounter++;
        if (periodicRecordingCounter == continuousToPeriodicUploadsRatio) {
          periodicRecordingCounter = 0;
          final OngoingRecording newRecording =
              controller.createPeriodicRecording(PERIODIC_RECORDING_NAME);
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
