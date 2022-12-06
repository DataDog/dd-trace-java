package com.datadog.profiling.controller.async;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.async.AsyncProfiler;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import java.time.Instant;
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
public class AsyncOngoingRecordingTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Instant start;
  @Mock private Instant end;

  private static AsyncProfiler asyncProfiler;
  private AsyncOngoingRecording ongoingRecording;

  @BeforeAll
  public static void setupAll() throws UnsupportedEnvironmentException {
    asyncProfiler = AsyncProfiler.getInstance();
  }

  @BeforeEach
  public void setup() throws Exception {
    ongoingRecording = new AsyncOngoingRecording(asyncProfiler, TEST_NAME);
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
