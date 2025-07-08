package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.ControllerContext;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.profiling.RecordingData;
import java.time.Instant;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
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
public class OpenJdkOngoingRecordingTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Instant start;
  @Mock private Instant end;
  @Mock private Recording recording;

  private OpenJdkOngoingRecording ongoingRecording;

  @BeforeEach
  public void setup() {
    assumeFalse(JavaVirtualMachine.isJ9());
    when(recording.getState()).thenReturn(RecordingState.RUNNING);
    when(recording.getName()).thenReturn(TEST_NAME);

    ongoingRecording =
        new OpenJdkOngoingRecording(recording, new ControllerContext().snapshot(), true);
  }

  @Test
  public void testStop() {
    RecordingData data = ongoingRecording.stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    assertEquals(recording, ((OpenJdkRecordingData) data).getRecording());

    verify(recording).stop();
  }

  @Test
  public void testStopOnStopped() {
    when(recording.getState()).thenReturn(RecordingState.STOPPED);

    assertThrows(
        IllegalStateException.class,
        () -> {
          ongoingRecording.stop();
        });

    verify(recording, never()).stop();
  }

  @Test
  public void testSnapshot() {
    final RecordingData recordingData = ongoingRecording.snapshot(start);
    assertTrue(recordingData instanceof OpenJdkRecordingData);
    assertEquals(TEST_NAME, recordingData.getName());
    assertEquals(start, recordingData.getStart());
    assertEquals(
        ((OpenJdkRecordingData) recordingData).getRecording().getStopTime(),
        recordingData.getEnd());
    assertNotEquals(
        recording,
        ((OpenJdkRecordingData) recordingData).getRecording(),
        "make sure we didn't get our mocked recording");

    // We got real recording so we should clean it up
    recordingData.release();

    verify(recording, never()).stop();
  }

  @Test
  public void testSnapshotOnStopped() {
    when(recording.getState()).thenReturn(RecordingState.STOPPED);

    assertThrows(
        IllegalStateException.class,
        () -> {
          ongoingRecording.snapshot(start);
        });

    verify(recording, never()).stop();
  }

  @Test
  public void testClose() {
    ongoingRecording.close();

    verify(recording).close();
  }
}
