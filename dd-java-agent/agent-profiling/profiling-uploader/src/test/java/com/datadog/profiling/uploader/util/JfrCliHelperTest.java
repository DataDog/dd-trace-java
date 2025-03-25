package com.datadog.profiling.uploader.util;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.trace.api.Platform;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.relocate.api.IOLogger;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
public class JfrCliHelperTest {

  private static final String RECORDING_RESOURCE = "/test-recording.jfr";
  private static final String RECODING_NAME_PREFIX = "test-recording-";

  private static final int SEQUENCE_NUMBER = 123;
  private static final int PROFILE_START = 1000;
  private static final int PROFILE_END = 1100;

  @Mock private IOLogger ioLogger;

  @Test
  public void testInvokeOn() throws Exception {
    // J9 may have 'jfr' command present but it requires additional setup
    // Currently we don't support J9 JFR so we can safely skip this test
    Assumptions.assumeFalse(Platform.isJ9());
    final RecordingData recording = mockRecordingData();

    JfrCliHelper.invokeOn(recording, ioLogger);

    if (!hasJfr()) {
      verify(ioLogger).error(eq("Failed to gather information on recording, can't find `jfr`"));
    } else {
      Set<String> messages = new HashSet<String>();
      messages.add("Event: jdk.Metadata, size = 208257, count = 3");
      messages.add("Event: jdk.ClassLoad, size = 140225, count = 6679");
      messages.add("Event: jdk.SystemProcess, size = 132771, count = 1389");
      messages.add("Event: jdk.NativeLibrary, size = 83591, count = 927");
      messages.add("Event: jdk.ClassDefine, size = 78876, count = 4624");
      messages.add("Event: jdk.BooleanFlag, size = 66249, count = 1935");
      messages.add("Event: jdk.ActiveSetting, size = 48732, count = 1470");
      messages.add("Event: jdk.JavaMonitorWait, size = 35170, count = 1232");
      messages.add("Event: jdk.InitialSystemProperty, size = 31164, count = 81");

      // tried using `messages.remove(message)` to guarantee we are not duplicating
      // the calls but Mockito complained for some reasons
      verify(ioLogger, times(messages.size()))
          .error(argThat(message -> messages.contains(message)));
    }
  }

  private boolean hasJfr() {
    return Files.exists(Paths.get(System.getProperty("java.home"), "bin", "jfr"));
  }

  private RecordingData mockRecordingData() throws IOException {
    return mockRecordingData(false);
  }

  private RecordingData mockRecordingData(boolean zip) throws IOException {
    final RecordingData recordingData = mock(RecordingData.class, withSettings().lenient());
    when(recordingData.getStream())
        .then(
            (Answer<InputStream>)
                invocation -> spy(new RecordingInputStream(recordingStream(zip))));
    when(recordingData.getName()).thenReturn(RECODING_NAME_PREFIX + SEQUENCE_NUMBER);
    when(recordingData.getStart()).thenReturn(Instant.ofEpochSecond(PROFILE_START));
    when(recordingData.getEnd()).thenReturn(Instant.ofEpochSecond(PROFILE_END));
    return recordingData;
  }

  private static InputStream recordingStream(boolean gzip) throws IOException {
    InputStream dataStream = JfrCliHelper.class.getResourceAsStream(RECORDING_RESOURCE);
    if (gzip) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (GZIPOutputStream zos = new GZIPOutputStream(baos)) {
        IOUtils.copy(dataStream, zos);
      }
      dataStream = new ByteArrayInputStream(baos.toByteArray());
    }
    return new BufferedInputStream(dataStream);
  }
}
