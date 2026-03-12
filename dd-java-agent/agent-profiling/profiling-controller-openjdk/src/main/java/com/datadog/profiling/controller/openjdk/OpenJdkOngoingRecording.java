package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nonnull;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenJdkOngoingRecording implements OngoingRecording {
  private static final Logger log = LoggerFactory.getLogger(OpenJdkOngoingRecording.class);

  private final JfrProfilerSettings configMemento;

  private final Recording recording;

  OpenJdkOngoingRecording(
      String recordingName,
      Map<String, String> settings,
      int maxSize,
      Duration maxAge,
      ControllerContext.Snapshot context,
      boolean jfrStackDepthSettingApplied) {
    this(
        recordingName,
        settings,
        maxSize,
        maxAge,
        ConfigProvider.getInstance(),
        context,
        jfrStackDepthSettingApplied);
  }

  OpenJdkOngoingRecording(
      String recordingName,
      Map<String, String> settings,
      int maxSize,
      Duration maxAge,
      ConfigProvider configProvider,
      ControllerContext.Snapshot context,
      boolean jfrStackDepthSettingApplied) {
    log.debug("Creating new recording: {}", recordingName);
    recording = new Recording();
    recording.setName(recordingName);
    recording.setSettings(settings);
    recording.setMaxSize(maxSize);
    recording.setMaxAge(maxAge);
    if (context.isDatadogProfilerEnabled()) {
      disableOverriddenEvents(context);
    }
    recording.start();
    log.debug("Recording {} started", recordingName);
    this.configMemento =
        new JfrProfilerSettings(configProvider, context, jfrStackDepthSettingApplied);
  }

  OpenJdkOngoingRecording(
      Recording recording,
      ControllerContext.Snapshot context,
      boolean jfrStackDepthSettingApplied) {
    this.recording = recording;
    if (context.isDatadogProfilerEnabled()) {
      disableOverriddenEvents(context);
    }
    recording.start();
    log.debug("Recording {} started", recording.getName());
    this.configMemento =
        new JfrProfilerSettings(ConfigProvider.getInstance(), context, jfrStackDepthSettingApplied);
  }

  private void disableOverriddenEvents(ControllerContext.Snapshot context) {
    for (ProfilingMode mode : context.getDatadogProfilingModes()) {
      switch (mode) {
        case CPU:
          {
            // CPU execution profiling will take over these events, including
            // jdk.CPUTimeSample (JEP 518, JDK 25+) which is enabled as a fallback
            // when ddprof is unavailable
            log.debug("Disabling built-in CPU profiling events");
            recording.disable("jdk.ExecutionSample");
            recording.disable("jdk.NativeMethodSample");
            recording.disable("jdk.CPUTimeSample");
            recording.disable("jdk.CPUTimeSamplesLost");
            break;
          }
        case WALL:
          {
            // wall-time profiling will take over these events
            log.debug("Disabling built-in wall-time tracing events");
            recording.disable("jdk.JavaMonitorWait");
            recording.disable("jdk.ThreadPark");
            recording.disable("jdk.ThreadSleep");
            break;
          }
        case ALLOCATION:
          {
            // allocation profiling will take over these events
            log.debug("Disabling built-in allocation profiling events");
            recording.disable("jdk.ObjectAllocationOutsideTLAB");
            recording.disable("jdk.ObjectAllocationInNewTLAB");
            recording.disable("jdk.ObjectAllocationSample");
            break;
          }
        case MEMLEAK:
          {
            // memleak profiling will take over these events
            log.debug("Disabling built-in memory leak profiling events");
            recording.disable("jdk.OldObjectSample");
            break;
          }
        default:
          break;
      }
    }
  }

  @Override
  public RecordingData stop() {
    if (recording.getState() != RecordingState.RUNNING) {
      throw new IllegalStateException("Cannot stop recording that is not running");
    }
    // dump the config to the current JFR recording
    configMemento.publish();

    recording.stop();
    return new OpenJdkRecordingData(recording, ProfilingSnapshot.Kind.PERIODIC);
  }

  // @VisibleForTesting
  final RecordingData snapshot(@Nonnull final Instant start) {
    return snapshot(start, ProfilingSnapshot.Kind.PERIODIC);
  }

  @Override
  public RecordingData snapshot(
      @Nonnull final Instant start, @Nonnull ProfilingSnapshot.Kind kind) {
    if (recording.getState() != RecordingState.RUNNING) {
      throw new IllegalStateException("Cannot snapshot recording that is not running");
    }

    // dump the config to the current JFR recording
    configMemento.publish();
    final Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot();
    snapshot.setName(recording.getName()); // Copy name from original recording
    // Since we just requested a snapshot, the end time of the snapshot will be
    // very close to now, so use that end time to minimize the risk of gaps or
    // overlaps in the data.
    return new OpenJdkRecordingData(snapshot, start, snapshot.getStopTime(), kind);
  }

  @Override
  public void close() {
    recording.close();
  }
}
