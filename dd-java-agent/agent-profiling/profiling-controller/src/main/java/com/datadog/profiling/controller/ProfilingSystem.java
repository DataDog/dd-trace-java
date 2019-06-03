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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sets up the profiling strategy and schedules the profiling recordings. */
@Slf4j
public final class ProfilingSystem {
  public static final ThreadGroup THREAD_GROUP = new ThreadGroup("Datadog Profiler");

  private static final Logger LOGGER = LoggerFactory.getLogger(ProfilingSystem.class);
  private static final AtomicInteger RECORDING_SEQUENCE_NUMBER = new AtomicInteger();

  private final ScheduledExecutorService executorService =
      Executors.newScheduledThreadPool(1, new ProfilingRecorderThreadFactory());
  private final Controller controller;
  // For now only support one callback. Multiplex as needed.
  private final RecordingDataListener dataListener;

  private final Duration delay;
  private final Duration period;
  private final Duration recordingDuration;

  private volatile ScheduledFuture<?> scheduledProfilingRecordings;
  private volatile ScheduledFuture<?> scheduledHarvester;
  private volatile RecordingData continuousRecording;
  private volatile RecordingData ongoingProfilingRecording;

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
      final Duration recordingDuration)
      throws ConfigurationException {
    this.controller = controller;
    this.dataListener = dataListener;
    this.delay = delay;
    this.period = period;
    this.recordingDuration = recordingDuration;
    if (period.minus(recordingDuration).isNegative()) {
      throw new ConfigurationException("Period must be larger than recording duration.");
    }
  }

  /**
   * Starts the scheduling of profiling recordings and the continuous recording.
   *
   * @throws IOException if there was a problem reading configuration files etc.
   */
  public final void start() throws IOException {
    continuousRecording = controller.createContinuousRecording("dd_profiler_continuous");
    startSchedulingProfilingRecordings();
  }

  /**
   * Schedules the repeated recording of profiling recordings.
   *
   * @throws IOException if there was a problem reading the profiling settings.
   */
  private void startSchedulingProfilingRecordings() throws IOException {
    scheduledProfilingRecordings =
        executorService.scheduleAtFixedRate(
            new RecordingCreator(), delay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    scheduledHarvester =
        executorService.scheduleAtFixedRate(
            new RecordingHarvester(),
            delay.toMillis() + recordingDuration.toMillis() + 50,
            period.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  /** Shuts down the profiling system. */
  public final void shutdown() {
    executorService.shutdownNow();

    if (scheduledHarvester != null) {
      scheduledHarvester.cancel(true);
    }
    if (scheduledProfilingRecordings != null) {
      scheduledProfilingRecordings.cancel(true);
    }
    RecordingData recording = ongoingProfilingRecording;
    if (recording != null) {
      recording.release();
      ongoingProfilingRecording = null;
    }
    recording = continuousRecording;
    if (recording != null) {
      recording.release();
      continuousRecording = null;
    }

    try {
      executorService.awaitTermination(10, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      log.error("Wait for executor shutdown interrupted");
    }
  }

  public void triggerSnapshot() throws IOException {
    dataListener.onNewData(controller.snapshot());
  }

  /**
   * Package private helper class for scheduling the creation of recordings.
   *
   * @author Marcus Hirt
   */
  private final class RecordingCreator implements Runnable {

    @Override
    public void run() {
      try {
        if (ongoingProfilingRecording == null) {
          ongoingProfilingRecording =
              controller.createRecording(
                  "dd-profiling-" + RECORDING_SEQUENCE_NUMBER.getAndIncrement(), recordingDuration);
        } else {
          log.warn("Skipped creating profiling recording, since one was already underway.");
        }
      } catch (final IOException e) {
        log.error("Failed to create a profiling recording!", e);
      }
    }
  }

  private final class RecordingHarvester implements Runnable {
    private static final long RETRY_DELAY = 100;

    @Override
    public void run() {
      final RecordingData recording = ongoingProfilingRecording;
      if (recording != null) {
        if (!recording.isAvailable()) {
          // We were called too soon. Let's try again in a bit.
          executorService.schedule(this, RETRY_DELAY, TimeUnit.MILLISECONDS);
          log.warn("Profiling Recording wasn't done. Rescheduled check in {} ms", RETRY_DELAY);
        } else {
          dataListener.onNewData(recording);
          ongoingProfilingRecording = null;
        }
      }
    }
  }
}
