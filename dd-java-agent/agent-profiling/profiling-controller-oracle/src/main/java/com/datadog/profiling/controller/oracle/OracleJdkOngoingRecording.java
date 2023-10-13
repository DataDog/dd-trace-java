package com.datadog.profiling.controller.oracle;

import com.datadog.profiling.controller.OngoingRecording;
import datadog.trace.api.profiling.ProfilingSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OracleJdkOngoingRecording implements OngoingRecording {
  private static final Logger log = LoggerFactory.getLogger(OracleJdkOngoingRecording.class);
  private final String name;
  private final JfrMBeanHelper helper;
  private final ObjectName recordingId;
  private final Instant start;

  OracleJdkOngoingRecording(
      @Nonnull final JfrMBeanHelper helper,
      @Nonnull String name,
      long maxSize,
      @Nonnull Duration maxAge,
      @Nonnull Map<String, String> eventSettings)
      throws IOException {
    this.name = name;
    this.helper = helper;

    recordingId = helper.newRecording(name, maxSize, maxAge, eventSettings);
    start = Instant.now();
  }

  @Override
  public OracleJdkRecordingData stop() {
    try {
      log.debug("Stopping recording {}", name);
      helper.stopRecording(recordingId);
      OracleJdkRecordingData data =
          new OracleJdkRecordingData(
              name,
              recordingId,
              start,
              getEndTime(helper, recordingId, Instant.now()),
              ProfilingSnapshot.Kind.PERIODIC,
              helper);
      log.debug("Recording {} has been stopped and its data collected", name);
      return data;
    } catch (IOException e) {
      throw new RuntimeException("Unable to stop recording " + name, e);
    }
  }

  // @VisibleForTesting
  final OracleJdkRecordingData snapshot(@Nonnull final Instant start) {
    return snapshot(start, ProfilingSnapshot.Kind.PERIODIC);
  }

  @Override
  @Nonnull
  public OracleJdkRecordingData snapshot(
      @Nonnull final Instant start, @Nonnull ProfilingSnapshot.Kind kind) {
    log.debug("Taking recording snapshot for time range {} - {}", start, Instant.now());
    ObjectName targetName = recordingId;
    try {
      if (!(boolean) helper.getRecordingAttribute(targetName, "Running")) {
        throw new RuntimeException("Recording " + name + " is not active");
      }

      targetName = helper.cloneRecording(targetName);
      return new OracleJdkRecordingData(
          name, targetName, start, getEndTime(helper, targetName, Instant.now()), kind, helper);
    } catch (IOException e) {
      throw new RuntimeException("Unable to take snapshot for recording " + name, e);
    }
  }

  @Override
  public void close() {
    try {
      helper.closeRecording(recordingId);
    } catch (IOException e) {
      log.debug("Unable to close recording {}", name, e);
    }
  }

  private static Instant getEndTime(
      JfrMBeanHelper helper, ObjectName recordingId, Instant defaultEndTime) {
    try {
      return helper.getDataEndTime(recordingId);
    } catch (IOException e) {
      log.debug(
          "Unable to retrieve the data end time for recording {}. ({})",
          recordingId.getKeyProperty("name"),
          e.toString());
    }
    return defaultEndTime;
  }
}
