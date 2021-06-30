package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.auxiliary.AuxiliaryProfiler;
import com.datadog.profiling.auxiliary.AuxiliaryRecordingData;
import com.datadog.profiling.auxiliary.ProfilingMode;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenJdkOngoingRecording implements OngoingRecording {
  private static final Logger log = LoggerFactory.getLogger(OpenJdkOngoingRecording.class);

  private final Recording recording;
  private final OngoingRecording auxiliaryRecording;
  private final AuxiliaryProfiler auxiliaryProfiler;

  OpenJdkOngoingRecording(
      String recordingName, Map<String, String> settings, int maxSize, Duration maxAge) {
    log.debug("Creating new recording: {}", recordingName);
    recording = new Recording();
    recording.setName(recordingName);
    recording.setSettings(settings);
    recording.setMaxSize(maxSize);
    recording.setMaxAge(maxAge);
    this.auxiliaryProfiler = AuxiliaryProfiler.getInstance();
    if (auxiliaryProfiler.isEnabled()) {
      auxiliaryRecording = auxiliaryProfiler.start();
      if (auxiliaryRecording != null) {
        disableOverridenEvents();
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
        disableOverridenEvents();
      }
    } else {
      auxiliaryRecording = null;
    }
    recording.start();
    log.debug("Recording {} started", recording.getName());
  }

  private void disableOverridenEvents() {
    for (ProfilingMode mode : auxiliaryProfiler.enabledModes()) {
      switch (mode) {
        case CPU:
          {
            // CPU execution profiling will take over these events
            log.info("Disabling built-in CPU profiling events");
            recording.disable("jdk.ExecutionSample");
            recording.disable("jdk.NativeMethodSample");
            break;
          }
        case ALLOCATION:
          {
            // allocation profiling will take over these events
            log.info("Disabling built-in allocation profiling events");
            recording.disable("jdk.ObjectAllocationOutsideTLAB");
            recording.disable("jdk.ObjectAllocationInNewTLAB");
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

    recording.stop();
    OpenJdkRecordingData mainData = new OpenJdkRecordingData(recording);
    return auxiliaryRecording != null
        ? new AuxiliaryRecordingData(
            mainData.getStart(), mainData.getEnd(), mainData, auxiliaryRecording.stop())
        : mainData;
  }

  @Override
  public RecordingData snapshot(final Instant start) {
    if (recording.getState() != RecordingState.RUNNING) {
      throw new IllegalStateException("Cannot snapshot recording that is not running");
    }

    final Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot();
    snapshot.setName(recording.getName()); // Copy name from original recording
    // Since we just requested a snapshot, the end time of the snapshot will be
    // very close to now, so use that end time to minimize the risk of gaps or
    // overlaps in the data.
    OpenJdkRecordingData openJdkData =
        new OpenJdkRecordingData(snapshot, start, snapshot.getStopTime());
    return auxiliaryRecording != null
        ? new AuxiliaryRecordingData(
            start, snapshot.getStopTime(), openJdkData, auxiliaryRecording.snapshot(start))
        : openJdkData;
  }

  @Override
  public void close() {
    recording.close();
    if (auxiliaryRecording != null) {
      auxiliaryRecording.close();
    }
  }
}
