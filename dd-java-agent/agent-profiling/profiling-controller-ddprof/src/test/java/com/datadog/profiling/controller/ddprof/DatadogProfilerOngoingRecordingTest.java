package com.datadog.profiling.controller.ddprof;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.ddprof.DatadogProfiler;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.profiling.RecordingData;
import java.time.Instant;
import org.junit.Assume;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
// Proper unused stub detection doesn't work in junit5 yet,
// see https://github.com/mockito/mockito/issues/1540
@MockitoSettings(strictness = Strictness.LENIENT)
public class DatadogProfilerOngoingRecordingTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Instant start;
  @Mock private Instant end;

  private DatadogProfilerOngoingRecording ongoingRecording;

  @BeforeAll
  public static void setupAll() {
    // If the profiler couldn't be loaded, the reason why is saved.
    // This test assumes the profiler could be loaded.
    Assume.assumeNoException(
        "profiler not available", DdprofLibraryLoader.javaProfiler().getReasonNotLoaded());
  }

  @BeforeEach
  public void setup() throws Exception {
    ongoingRecording =
        new DatadogProfilerOngoingRecording(DatadogProfiler.newInstance(), TEST_NAME);
  }

  @AfterEach
  public void cleanup() {
    ongoingRecording.stop();
  }

  @Test
  public void testStop() {
    RecordingData data = ongoingRecording.stop();
  }

  @Test
  public void testSnapshot() {
    final RecordingData recordingData = ongoingRecording.snapshot(start);
    assertEquals(start, recordingData.getStart());

    // We got real recording so we should clean it up
    recordingData.release();
  }

  @Test
  public void testClose() {
    ongoingRecording.close();
  }
}
