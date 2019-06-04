package com.datadog.profiling.controller.openjdk;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import jdk.jfr.Recording;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OpenJdkRecordingDataTest {

  private static final String TEST_NAME = "recording name";

  @Mock Instant start;
  @Mock Instant end;
  @Mock Instant customStart;
  @Mock Instant customEnd;
  @Mock private InputStream stream;
  @Mock private InputStream customStream;
  @Mock private Recording recording;

  private OpenJdkRecordingData recordingData;
  private OpenJdkRecordingData customRecordingData;

  @Before
  public void setup() throws IOException {
    when(recording.getStream(start, end)).thenReturn(stream);
    when(recording.getStream(customStart, customEnd)).thenReturn(customStream);
    when(recording.getStartTime()).thenReturn(start);
    when(recording.getStopTime()).thenReturn(end);
    when(recording.getName()).thenReturn(TEST_NAME);

    recordingData = new OpenJdkRecordingData(recording);
    customRecordingData = new OpenJdkRecordingData(recording, customStart, customEnd);
  }

  @Test
  public void testGetStream() throws IOException {
    assertEquals(stream, recordingData.getStream());
  }

  @Test
  public void testRelease() {
    recordingData.release();
    verify(recording).close();
  }

  @Test
  public void testGetName() {
    assertEquals(TEST_NAME, recordingData.getName());
  }

  @Test
  public void testToString() {
    assertThat(recordingData.toString(), containsString(TEST_NAME));
  }

  @Test
  public void testGetStart() {
    assertEquals(start, recordingData.getStart());
  }

  @Test
  public void testGetEnd() {
    assertEquals(end, recordingData.getEnd());
  }

  @Test
  public void testCustomGetStream() throws IOException {
    assertEquals(customStream, customRecordingData.getStream());
  }

  @Test
  public void testCustomGetStart() {
    assertEquals(customStart, customRecordingData.getStart());
  }

  @Test
  public void testCustomGetEnd() {
    assertEquals(customEnd, customRecordingData.getEnd());
  }

  @Test
  public void getRecording() {
    assertEquals(recording, recordingData.getRecording());
  }
}
