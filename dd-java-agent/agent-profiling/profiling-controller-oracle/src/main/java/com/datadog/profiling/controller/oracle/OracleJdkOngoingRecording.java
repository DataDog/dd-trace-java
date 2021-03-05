package com.datadog.profiling.controller.oracle;

import com.datadog.profiling.controller.OngoingRecording;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.management.ObjectName;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OracleJdkOngoingRecording implements OngoingRecording {
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
          new OracleJdkRecordingData(name, recordingId, start, Instant.now(), helper);
      log.debug("Recording {} has been stopped and its data collected", name);
      return data;
    } catch (IOException e) {
      throw new RuntimeException("Unable to stop recording " + name, e);
    }
  }

  @Override
  @Nonnull
  public OracleJdkRecordingData snapshot(@Nonnull final Instant start, @Nonnull final Instant end) {
    log.warn("Taking recording snapshot for time range {} - {}", start, end);
    ObjectName targetName = recordingId;
    try {
      if (!(boolean) helper.getRecordingAttribute(targetName, "Running")) {
        throw new RuntimeException("Recording " + name + " is not active");
      }

      targetName = helper.cloneRecording(targetName);
      return new OracleJdkRecordingData(name, targetName, start, end, helper);
    } catch (IOException e) {
      throw new RuntimeException("Unable to take snapshot for recording " + name, e);
    }
  }

  @Override
  public void close() {
    try {
      helper.closeRecording(recordingId);
    } catch (IOException e) {
      log.warn("Unable to close recording {}", name, e);
    }
  }
}
