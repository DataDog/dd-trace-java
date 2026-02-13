package com.datadog.profiling.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.profiling.scrubber.JfrScrubber;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.api.profiling.RecordingType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ScrubRecordingDataListenerTest {

  @TempDir Path tempDir;

  private RecordingDataListener delegate;
  private JfrScrubber scrubber;
  private RecordingData mockData;

  @BeforeEach
  void setUp() throws IOException {
    delegate = mock(RecordingDataListener.class);
    scrubber = mock(JfrScrubber.class);
    mockData = mock(RecordingData.class);
    when(mockData.getStart()).thenReturn(Instant.now());
    when(mockData.getEnd()).thenReturn(Instant.now());
    when(mockData.getKind()).thenReturn(ProfilingSnapshot.Kind.PERIODIC);
    when(mockData.getName()).thenReturn("test");
    when(mockData.getStream())
        .thenReturn(new RecordingInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3})));
  }

  @Test
  void delegatesScrubbedData() throws Exception {
    ScrubRecordingDataListener listener =
        new ScrubRecordingDataListener(delegate, scrubber, tempDir, false);

    listener.onNewData(RecordingType.CONTINUOUS, mockData, false);

    ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(delegate).onNewData(eq(RecordingType.CONTINUOUS), captor.capture(), eq(false));
    assertTrue(
        captor.getValue() instanceof ScrubRecordingDataListener.ScrubbedRecordingData,
        "Delegate should receive ScrubbedRecordingData");
    verify(mockData).release();
  }

  @Test
  void usesExistingFilePathWhenAvailable() throws Exception {
    // Simulate a file-backed recording (like ddprof)
    Path existingFile = tempDir.resolve("existing.jfr");
    Files.write(existingFile, new byte[] {1, 2, 3});
    when(mockData.getPath()).thenReturn(existingFile);

    ScrubRecordingDataListener listener =
        new ScrubRecordingDataListener(delegate, scrubber, tempDir, false);

    listener.onNewData(RecordingType.CONTINUOUS, mockData, false);

    // Scrubber should be called with the existing file path, not a temp file
    ArgumentCaptor<Path> inputCaptor = ArgumentCaptor.forClass(Path.class);
    ArgumentCaptor<Path> outputCaptor = ArgumentCaptor.forClass(Path.class);
    verify(scrubber).scrubFile(inputCaptor.capture(), outputCaptor.capture());
    assertTrue(
        inputCaptor.getValue().equals(existingFile),
        "Should use existing file path as scrub input");
    verify(delegate).onNewData(eq(RecordingType.CONTINUOUS), any(RecordingData.class), eq(false));
  }

  @Test
  void failClosedSkipsUpload() throws Exception {
    doThrow(new RuntimeException("scrub failed"))
        .when(scrubber)
        .scrubFile(any(Path.class), any(Path.class));

    ScrubRecordingDataListener listener =
        new ScrubRecordingDataListener(delegate, scrubber, tempDir, false);

    listener.onNewData(RecordingType.CONTINUOUS, mockData, false);

    verify(delegate, never()).onNewData(any(), any(), anyBoolean());
    verify(mockData).release();
  }

  @Test
  void failOpenDelegatesOriginalData() throws Exception {
    doThrow(new RuntimeException("scrub failed"))
        .when(scrubber)
        .scrubFile(any(Path.class), any(Path.class));

    ScrubRecordingDataListener listener =
        new ScrubRecordingDataListener(delegate, scrubber, tempDir, true);

    listener.onNewData(RecordingType.CONTINUOUS, mockData, false);

    verify(delegate).onNewData(eq(RecordingType.CONTINUOUS), eq(mockData), eq(false));
    verify(mockData, never()).release();
  }

  @Test
  void cleansTempFilesOnSuccess() throws Exception {
    ScrubRecordingDataListener listener =
        new ScrubRecordingDataListener(delegate, scrubber, tempDir, false);

    listener.onNewData(RecordingType.CONTINUOUS, mockData, false);

    // After success, temp input should be cleaned up, only scrubbed output remains
    long jfrCount = Files.list(tempDir).filter(p -> p.toString().contains("dd-scrub-in-")).count();
    assertTrue(jfrCount == 0, "Temp input files should be cleaned up");
  }
}
