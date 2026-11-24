package com.datadog.profiling.uploader.util;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.SystemProperties;
import datadog.logging.IOLogger;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
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
import jdk.jfr.AnnotationElement;
import jdk.jfr.EventFactory;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    assumeFalse(JavaVirtualMachine.isJ9());
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
    return Files.exists(Paths.get(SystemProperties.get("java.home"), "bin", "jfr"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.example.OrderProcessed",
        "com.example.CommandeValidée",
        "com.exemple.café.OrderProcessed",
        "com.example.订单已处理",
        "com.例子.OrderProcessed"
      })
  public void preservesUnicodeEventNames(String eventName) throws Exception {
    assumeFalse(JavaVirtualMachine.isJ9());
    assumeTrue(hasJfr());
    RecordingData recording = mock(RecordingData.class);
    try (InputStream input = new ByteArrayInputStream(UnicodeRecording.create(eventName))) {
      when(recording.getStream()).thenReturn(new RecordingInputStream(input));
      JfrCliHelper.invokeOn(recording, ioLogger);
    }

    verify(ioLogger)
        .error(
            argThat(message ->
                message.startsWith("Event: " + eventName + ", size = ")
                    && message.endsWith(", count = 1")));
  }

  // Load JFR classes only after the assumptions, so JVMs without JFR can skip the test.
  private static final class UnicodeRecording {
    static byte[] create(String eventName) throws IOException {
      EventFactory factory =
          EventFactory.create(
              singletonList(new AnnotationElement(Name.class, eventName)), emptyList());
      try (Recording recording = new Recording()) {
        recording.enable(eventName).withoutStackTrace();
        recording.start();
        factory.newEvent().commit();
        recording.stop();

        try (InputStream input = recording.getStream(null, null)) {
          return IOUtils.toByteArray(input);
        }
      } finally {
        factory.unregister();
      }
    }
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
