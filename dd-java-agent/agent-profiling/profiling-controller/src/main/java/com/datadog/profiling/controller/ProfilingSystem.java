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

import com.datadog.profiling.util.ProfilingThreadFactory;
import datadog.trace.core.util.SystemAccess;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** Sets up the profiling strategy and schedules the profiling recordings. */
@Slf4j
public final class ProfilingSystem {
  static final String RECORDING_NAME = "dd-profiling";

  private static final long TERMINATION_TIMEOUT = 10;
  public static final int DEFAULT_STACK_DEPTH = 256;
  public static final int MAX_STACK_DEPTH = 1024;

  private final ScheduledExecutorService executorService;
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
        Executors.newScheduledThreadPool(
            1, new ProfilingThreadFactory("dd-profiler-recording-scheduler")),
        ThreadLocalRandom.current());
  }

  ProfilingSystem(
      final Controller controller,
      final RecordingDataListener dataListener,
      final Duration baseStartupDelay,
      final Duration startupDelayRandomRange,
      final Duration uploadPeriod,
      final boolean isStartingFirst,
      final ScheduledExecutorService executorService,
      final ThreadLocalRandom threadLocalRandom)
      throws ConfigurationException {
    this.controller = controller;
    this.dataListener = dataListener;
    this.uploadPeriod = uploadPeriod;
    this.isStartingFirst = isStartingFirst;
    this.executorService = executorService;

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
      executorService.schedule(
          this::startProfilingRecording, startupDelay.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  protected static int stackDepthFromClient(final String userSpecifiedDepth) {
    try {
      final int stackDepth = Integer.parseInt(userSpecifiedDepth);

      // client specified a value considered safe
      if (stackDepth < MAX_STACK_DEPTH) {
        log.info("skip setting JFR.configure stackdepth, using " + userSpecifiedDepth);
        return stackDepth;
      }

      // limit how deep a stack depth we'll collect
      log.warn(userSpecifiedDepth + " exceeds maximum value allowed by Datadog agent");
      return MAX_STACK_DEPTH;
    } catch (final NumberFormatException e) { // "this should never happen"
      log.warn("malformed arg: " + userSpecifiedDepth);
    }

    return DEFAULT_STACK_DEPTH;
  }

  private static void setMaxStackDepth() {
    int maxFrames = ProfilingSystem.DEFAULT_STACK_DEPTH;

    final Optional<String> userSpecifiedStackDepth =
        readJFRStackDepth(SystemAccess.getVMArguments());
    if (userSpecifiedStackDepth.isPresent()) {
      maxFrames = stackDepthFromClient(userSpecifiedStackDepth.get());
    }

    final String result =
        SystemAccess.executeDiagnosticCommand(
            "jfrConfigure",
            new Object[] {new String[] {"stackdepth=" + maxFrames}},
            new String[] {String[].class.getName()});

    log.info("setting JFR.configure stackdepth: {}", result);
  }

  protected static Optional<String> readJFRStackDepth(final List<String> vmArgs) {
    final String JFR_OPTIONS_ARG = "-XX:FlightRecorderOptions=";
    return vmArgs.stream()
        .filter(arg -> arg.startsWith(JFR_OPTIONS_ARG))
        .map(arg -> arg.replace(JFR_OPTIONS_ARG, "").trim())
        .map(opts -> readKVOption(opts.split(","), "stackdepth"))
        .filter(Objects::nonNull)
        .findFirst();
  }

  private static String readKVOption(final String[] kvOpts, final String key) {
    for (final String kvStr : kvOpts) {
      final String[] kv = kvStr.split("=");
      if (kv.length == 2 && kv[0].trim().equals(key)) {
        return kv[1].trim();
      }
    }
    return null;
  }

  private void startProfilingRecording() {
    try {
      final Instant now = Instant.now();

      // set JFR.configure stackdepth here because
      // 1) it's prior to starting the recording; once recording starts there is no setting
      // _max_frames
      // 2) likely has given JMX a chance to initialize
      setMaxStackDepth();

      recording = controller.createRecording(RECORDING_NAME);
      executorService.scheduleAtFixedRate(
          new SnapshotRecording(now),
          uploadPeriod.toMillis(),
          uploadPeriod.toMillis(),
          TimeUnit.MILLISECONDS);
      started = true;
    } catch (final Throwable t) {
      log.error("Fatal exception during profiling startup", t);
      throw t;
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

  private final class SnapshotRecording implements Runnable {

    private Instant lastSnapshot;

    SnapshotRecording(final Instant startTime) {
      lastSnapshot = startTime;
    }

    @Override
    public void run() {
      final RecordingType recordingType = RecordingType.CONTINUOUS;
      try {
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
