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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/** Sets up the profiling strategy and schedules the profiling recordings. */
@Slf4j
public final class ProfilingSystem {
  static final String PROFILING_RECORDING_NAME_PREFIX = "dd-profiling-";
  static final String CONTINUOUS_RECORDING_NAME = "dd-profiling-continuous";

  private static final long TERMINATION_TIMEOUT = 10;

  private final ScheduledExecutorService executorService;
  private final AtomicInteger recordingSequenceCounter;
  private final Controller controller;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;

  private final Duration delay;
  private final Duration period;
  private final Duration recordingDuration;
  private final Duration continuousUploadPeriod;

  private OngoingRecording continuousRecording;
  private final AtomicReference<OngoingRecording> ongoingProfilingRecording =
      new AtomicReference<>(null);

  /**
   * Constructor.
   *
   * @param controller implementation specific controller of profiling machinery
   * @param dataListener the listener for data being produced.
   * @param delay the delay to wait before starting capturing data.
   * @param period the period between data captures.
   * @param recordingDuration the duration for each recording captured.
   * @throws ConfigurationException if the configuration information was bad.
   */
  public ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration delay,
      final Duration period,
      final Duration recordingDuration,
      final Duration continuousUploadPeriod)
      throws ConfigurationException {
    this(
        controller,
        dataListener,
        delay,
        period,
        recordingDuration,
        continuousUploadPeriod,
        Executors.newScheduledThreadPool(1, new ProfilingThreadFactory()),
        new AtomicInteger());
  }

  /** Constructor visible for testing. */
  ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration delay,
      final Duration period,
      final Duration recordingDuration,
      final Duration continuousUploadPeriod,
      final ScheduledExecutorService executorService,
      final AtomicInteger recordingSequenceCounter)
      throws ConfigurationException {
    this.controller = controller;
    this.dataListener = dataListener;
    this.delay = delay;
    this.period = period;
    this.recordingDuration = recordingDuration;
    this.continuousUploadPeriod = continuousUploadPeriod;
    this.executorService = executorService;
    this.recordingSequenceCounter = recordingSequenceCounter;

    if (delay.isNegative()) {
      throw new ConfigurationException("Recording delay must not be negative.");
    }

    if (period.isNegative()) {
      throw new ConfigurationException("Recording duration must not be negative.");
    }

    if (!period.isZero() && period.minus(recordingDuration).isNegative()) {
      throw new ConfigurationException("Period must be larger than recording duration.");
    }

    if (continuousUploadPeriod.isNegative()) {
      throw new ConfigurationException(
          "Upload period for continuous recording must not ber negative.");
    }
  }

  public final void start() {
    continuousRecording = controller.createContinuousRecording(CONTINUOUS_RECORDING_NAME);
    if (!continuousUploadPeriod.isZero()) {
      executorService.scheduleAtFixedRate(
          new SnapshotRecording(), 0, continuousUploadPeriod.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (!period.isZero()) {
      executorService.scheduleAtFixedRate(
          new StartRecording(), delay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
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

    final OngoingRecording recording = ongoingProfilingRecording.getAndSet(null);
    if (recording != null) {
      recording.close();
    }
  }

  private final class StartRecording implements Runnable {

    @Override
    public void run() {
      try {
        final OngoingRecording recording =
            controller.createRecording(
                PROFILING_RECORDING_NAME_PREFIX + recordingSequenceCounter.getAndIncrement());
        if (ongoingProfilingRecording.compareAndSet(null, recording)) {
          executorService.schedule(
              new StopRecording(), recordingDuration.toMillis(), TimeUnit.MILLISECONDS);
        } else {
          // Note: this seems to be impossible to test because we do not allow recordings longer
          // than recording period
          recording.close();
          log.warn("Skipped creating profiling recording, since one was already underway.");
        }
      } catch (final Exception e) {
        log.error("Failed to create a profiling recording!", e);
      }
    }
  }

  private final class StopRecording implements Runnable {

    @Override
    public void run() {
      final OngoingRecording recording = ongoingProfilingRecording.getAndSet(null);
      if (recording != null) {
        dataListener.onNewData(RecordingType.PERIODIC, recording.stop());
      }
    }
  }

  private final class SnapshotRecording implements Runnable {

    private Instant lastSnapshot = Instant.EPOCH;

    @Override
    public void run() {
      final Instant now = Instant.now();
      try {
        final RecordingData recording = continuousRecording.snapshot(lastSnapshot, now);
        lastSnapshot = now;
        if (recording != null) {
          dataListener.onNewData(RecordingType.CONTINUOUS, recording);
        }
      } catch (final Exception e) {
        log.error("Cannot upload snapshot", e);
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
