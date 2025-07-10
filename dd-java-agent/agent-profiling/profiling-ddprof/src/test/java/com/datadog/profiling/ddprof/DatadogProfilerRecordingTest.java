package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.OperatingSystem;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogProfilerRecordingTest {

  private DatadogProfiler profiler;
  private DatadogProfilerRecording recording;

  @BeforeEach
  void setup() {
    Assumptions.assumeTrue(OperatingSystem.isLinux());
    Assumptions.assumeTrue(JavaProfilerLoader.REASON_NOT_LOADED == null, "Profiler not available");
    profiler = DatadogProfiler.newInstance(ConfigProvider.getInstance());
    Assumptions.assumeFalse(profiler.isActive());
    recording = (DatadogProfilerRecording) profiler.start();
    Assumptions.assumeTrue(recording != null);
  }

  @AfterEach
  void shutdown() {
    // Apparently, failed 'assume' does not prevent shutdown from running
    // Do a sanity check before invoking profiler methods
    if (profiler != null && recording != null) {
      profiler.stop(recording);
    }
  }

  @Test
  void testClose() {
    Assumptions.assumeTrue(JavaProfilerLoader.REASON_NOT_LOADED == null, "Profiler not available");
    assertTrue(Files.exists(recording.getRecordingFile()));
    recording.close();
    assertFalse(Files.exists(recording.getRecordingFile()));
  }

  @Test
  void testStop() {
    Assumptions.assumeTrue(JavaProfilerLoader.REASON_NOT_LOADED == null, "Profiler not available");
    RecordingData data = recording.stop();
    assertNotNull(data);
    assertTrue(Files.exists(recording.getRecordingFile()));
  }

  @Test
  void testSnapshot() throws Exception {
    Assumptions.assumeTrue(JavaProfilerLoader.REASON_NOT_LOADED == null, "Profiler not available");
    RecordingData data = recording.snapshot(Instant.now());
    assertNotNull(data);
    assertTrue(Files.exists(recording.getRecordingFile()));
    InputStream inputStream = data.getStream();
    assertNotNull(inputStream);
    assertTrue(inputStream.available() > 0);
  }
}
