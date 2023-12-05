package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Platform;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.Assume;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DatadogProfilerRecordingTest {
  private static final Logger log = LoggerFactory.getLogger(DatadogProfilerRecordingTest.class);

  private DatadogProfiler profiler;
  private DatadogProfilerRecording recording;

  @BeforeEach
  void setup() throws Exception {
    Assume.assumeTrue(Platform.isLinux());
    profiler = DatadogProfiler.newInstance(ConfigProvider.getInstance());
    log.info(
        "Datadog Profiler: available={}, active={}", profiler.isAvailable(), profiler.isActive());
    if (profiler.isAvailable()) {
      Assume.assumeFalse(profiler.isActive());

      recording = (DatadogProfilerRecording) profiler.start();
      Assume.assumeTrue(recording != null);
    } else {
      // Datadog profiler not available
    }
  }

  @AfterEach
  void shutdown() throws Exception {
    // Apparently, failed 'assume' does not prevent shutdown from running
    // Do a sanity check before invoking profiler methods
    if (profiler != null && recording != null) {
      profiler.stop(recording);
    }
  }

  @Test
  void testClose() throws Exception {
    if (!profiler.isAvailable()) {
      log.warn("Datadog Profiler not available. Skipping test.");
      return;
    }
    assertTrue(Files.exists(recording.getRecordingFile()));
    recording.close();
    assertFalse(Files.exists(recording.getRecordingFile()));
  }

  @Test
  void testStop() throws Exception {
    if (!profiler.isAvailable()) {
      log.warn("Datadog Profiler not available. Skipping test.");
      return;
    }
    RecordingData data = recording.stop();
    assertNotNull(data);
    assertTrue(Files.exists(recording.getRecordingFile()));
  }

  @Test
  void testSnapshot() throws Exception {
    if (!profiler.isAvailable()) {
      log.warn("Datadog Profiler not available. Skipping test.");
      return;
    }
    RecordingData data = recording.snapshot(Instant.now());
    assertNotNull(data);
    assertTrue(Files.exists(recording.getRecordingFile()));
    InputStream inputStream = data.getStream();
    assertNotNull(inputStream);
    assertTrue(inputStream.available() > 0);
  }
}
