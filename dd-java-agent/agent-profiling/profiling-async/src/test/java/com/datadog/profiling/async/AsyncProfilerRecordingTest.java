package com.datadog.profiling.async;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.controller.RecordingData;
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

class AsyncProfilerRecordingTest {
  private static final Logger log = LoggerFactory.getLogger(AsyncProfilerRecordingTest.class);

  private AsyncProfiler profiler;
  private AsyncProfilerRecording recording;

  @BeforeEach
  void setup() throws Exception {
    profiler = new AsyncProfiler(ConfigProvider.getInstance());
    log.info(
        "Async Profiler: available={}, active={}", profiler.isAvailable(), profiler.isActive());
    Assume.assumeTrue(profiler.isAvailable());
    Assume.assumeFalse(profiler.isActive());

    recording = (AsyncProfilerRecording) profiler.start();
    Assume.assumeTrue(recording != null);
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
    assertTrue(Files.exists(recording.getRecordingFile()));
    recording.close();
    assertFalse(Files.exists(recording.getRecordingFile()));
  }

  @Test
  void testStop() throws Exception {
    RecordingData data = recording.stop();
    assertNotNull(data);
    assertTrue(Files.exists(recording.getRecordingFile()));
  }

  @Test
  void testSnapshot() throws Exception {
    RecordingData data = recording.snapshot(Instant.now());
    assertNotNull(data);
    assertTrue(Files.exists(recording.getRecordingFile()));
    InputStream inputStream = data.getStream();
    assertNotNull(inputStream);
    assertTrue(inputStream.available() > 0);
  }
}
