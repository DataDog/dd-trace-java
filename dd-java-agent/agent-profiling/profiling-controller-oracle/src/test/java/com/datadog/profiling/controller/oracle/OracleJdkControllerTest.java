package com.datadog.profiling.controller.oracle;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OracleJdkControllerTest {
  private OracleJdkController instance;

  @BeforeEach
  void setup() throws Exception {
    Properties props = new Properties();
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    instance = new OracleJdkController(configProvider);
  }

  @Test
  void createRecording() throws Exception {
    try (OracleJdkOngoingRecording recording = instance.createRecording("my_recording")) {
      assertNotNull(recording);
    }
  }

  @Test
  void createRecordingInvalid() {
    assertThrows(Throwable.class, () -> instance.createRecording(null));
  }

  @Test
  void getSnapshot() throws Exception {
    String recordingName = "my_recording";
    Instant start = Instant.now();
    try (OracleJdkOngoingRecording recording = instance.createRecording(recordingName)) {
      assertNotNull(recording);

      // sleep a while to allow a few events to be collected
      Thread.sleep(300);
      Instant end = Instant.now();
      OracleJdkRecordingData snapshot = recording.snapshot(start);

      assertNotNull(snapshot);
      assertEquals(start, snapshot.getStart());
      assertTrue(snapshot.getEnd().compareTo(end) >= 0);
      assertTrue(snapshot.getEnd().compareTo(Instant.now()) <= 0);
      assertEquals(recordingName, snapshot.getName());

      try (InputStream is = snapshot.getStream()) {
        assertNotNull(is);

        // make sure the stream can be read in whole and that it produces non-zero amount of data
        assertTrue(is.available() > 0);
        int len = 0;
        byte[] buff = new byte[256];
        while (is.read(buff) > -1) {
          len += buff.length;
        }
        assertTrue(len > 0);
      }
    }
  }

  @Test
  void getSnapshotAfterClose() throws Exception {
    String recordingName = "my_recording";
    Instant start = Instant.now();
    OracleJdkOngoingRecording recording = instance.createRecording(recordingName);
    assertNotNull(recording);
    recording.close();
    assertThrows(Throwable.class, () -> recording.snapshot(start));
  }

  @Test
  void getStopAndGetSnapshot() throws Exception {
    String recordingName = "my_recording";
    try (OracleJdkOngoingRecording recording = instance.createRecording(recordingName)) {
      assertNotNull(recording);

      // sleep a while to allow a few events to be collected
      Thread.sleep(300);
      OracleJdkRecordingData snapshot = recording.stop();

      assertNotNull(snapshot);
      assertEquals(recordingName, snapshot.getName());

      try (InputStream is = snapshot.getStream()) {
        assertNotNull(is);

        // make sure the stream can be read in whole and that it produces non-zero amount of data
        assertTrue(is.available() > 0);
        int len = 0;
        byte[] buff = new byte[256];
        while (is.read(buff) > -1) {
          len += buff.length;
        }
        assertTrue(len > 0);
      }
    }
  }

  @Test
  void getSnapshotAfterStop() throws Exception {
    String recordingName = "my_recording";
    Instant start = Instant.now();
    try (OracleJdkOngoingRecording recording = instance.createRecording(recordingName)) {
      assertNotNull(recording);

      // sleep a while to allow a few events to be collected
      Thread.sleep(300);
      OracleJdkRecordingData snapshot = recording.stop();

      assertNotNull(snapshot);
      assertEquals(recordingName, snapshot.getName());

      assertThrows(Throwable.class, () -> recording.snapshot(start));
    }
  }

  @Test
  void stopAfterClose() throws Exception {
    String recordingName = "my_recording";
    OracleJdkOngoingRecording recording = instance.createRecording(recordingName);
    assertNotNull(recording);

    recording.close();
    assertThrows(Throwable.class, recording::stop);
  }
}
