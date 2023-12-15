package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.auxiliary.AuxiliaryProfiler;
import com.datadog.profiling.auxiliary.AuxiliaryRecordingData;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.profiling.ProfilingListenersRegistry;
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

  private static final JfrProfilerSettings CONFIG_MEMENTO = JfrProfilerSettings.instance();

  private final Recording recording;
  private final OngoingRecording auxiliaryRecording;
  private final AuxiliaryProfiler auxiliaryProfiler;

  OpenJdkOngoingRecording(
      String recordingName, Map<String, String> settings, int maxSize, Duration maxAge) {
    this(recordingName, settings, maxSize, maxAge, ConfigProvider.getInstance());
  }

  OpenJdkOngoingRecording(
      String recordingName,
      Map<String, String> settings,
      int maxSize,
      Duration maxAge,
      ConfigProvider configProvider) {
    log.debug("Creating new recording: {}", recordingName);
    recording = new Recording();
    recording.setName(recordingName);
    recording.setSettings(settings);
    recording.setMaxSize(maxSize);
    recording.setMaxAge(maxAge);
    // for testing purposes we are supporting passing in a custom config provider
    this.auxiliaryProfiler =
        (configProvider == ConfigProvider.getInstance()
            ? AuxiliaryProfiler.getInstance()
            : new AuxiliaryProfiler(configProvider));
    if (auxiliaryProfiler.isEnabled()) {
      auxiliaryRecording = auxiliaryProfiler.start();
      if (auxiliaryRecording != null) {
        disableOverriddenEvents();
      }
    } else {
      auxiliaryRecording = null;
    }
    recording.start();
    log.debug("Recording {} started", recordingName);
  }

  OpenJdkOngoingRecording(Recording recording) {
    this.recording = recording;
    this.auxiliaryProfiler = AuxiliaryProfiler.getInstance();
    if (auxiliaryProfiler.isEnabled()) {
      auxiliaryRecording = auxiliaryProfiler.start();
      if (auxiliaryRecording != null) {
        disableOverriddenEvents();
      }
    } else {
      auxiliaryRecording = null;
    }
    recording.start();
    log.debug("Recording {} started", recording.getName());
  }

  private void disableOverriddenEvents() {
    for (ProfilingMode mode : auxiliaryProfiler.enabledModes()) {
      switch (mode) {
        case CPU:
          {
            // CPU execution profiling will take over these events
            log.debug("Disabling built-in CPU profiling events");
            recording.disable("jdk.ExecutionSample");
            recording.disable("jdk.NativeMethodSample");
            break;
          }
        case WALL:
          {
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
          {
            // do nothing
          }
      }
    }
  }

  @Override
  public RecordingData stop() {
    if (recording.getState() != RecordingState.RUNNING) {
      throw new IllegalStateException("Cannot stop recording that is not running");
    }
    // dump the config to the current JFR recording
    CONFIG_MEMENTO.publish();

    recording.stop();
    OpenJdkRecordingData mainData =
        new OpenJdkRecordingData(recording, ProfilingSnapshot.Kind.PERIODIC);
    return auxiliaryRecording != null
        ? new AuxiliaryRecordingData(
            mainData.getStart(),
            mainData.getEnd(),
            ProfilingSnapshot.Kind.PERIODIC,
            mainData,
            auxiliaryRecording.stop())
        : mainData;
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
    CONFIG_MEMENTO.publish();
    final Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot();
    snapshot.setName(recording.getName()); // Copy name from original recording
    // Since we just requested a snapshot, the end time of the snapshot will be
    // very close to now, so use that end time to minimize the risk of gaps or
    // overlaps in the data.
    OpenJdkRecordingData openJdkData =
        new OpenJdkRecordingData(snapshot, start, snapshot.getStopTime(), kind);
    RecordingData ret =
        auxiliaryRecording != null
            ? new AuxiliaryRecordingData(
                start,
                snapshot.getStopTime(),
                kind,
                openJdkData,
                auxiliaryRecording.snapshot(start, kind))
            : openJdkData;

    ProfilingListenersRegistry.getHost(ProfilingSnapshot.class).fireOnData(ret);
    return ret;
  }

  @Override
  public void close() {
    recording.close();
    if (auxiliaryRecording != null) {
      auxiliaryRecording.close();
    }
  }
}
