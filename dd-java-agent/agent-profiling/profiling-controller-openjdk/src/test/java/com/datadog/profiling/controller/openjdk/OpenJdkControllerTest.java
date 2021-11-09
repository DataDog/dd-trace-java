package com.datadog.profiling.controller.openjdk;

import static datadog.trace.api.Platform.isJavaVersion;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.jfr.JfpUtilsTest;
import datadog.trace.api.Config;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJdkControllerTest {

  private static final String TEST_NAME = "recording name";

  @Mock private Config config;

  @Test
  public void testCreateContinuousRecording() throws Exception {
    when(config.getProfilingTemplateOverrideFile()).thenReturn(JfpUtilsTest.OVERRIDES);
    OpenJdkController controller = new OpenJdkController(config);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      assertEquals(TEST_NAME, recording.getName());
      assertEquals(OpenJdkController.RECORDING_MAX_SIZE, recording.getMaxSize());
      assertEquals(OpenJdkController.RECORDING_MAX_AGE, recording.getMaxAge());
    }
  }

  @Test
  public void testOldObjectSampleIsDisabledOnUnsupportedVersion() throws Exception {
    OpenJdkController controller = new OpenJdkController(config);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
          || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
          || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
          || isJavaVersionAtLeast(17))) {
        assertEquals(
            false,
            Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
      }
    }
  }

  @Test
  public void testOldObjectSampleIsStillOverriddenOnUnsupportedVersion() throws Exception {
    when(config.getProfilingTemplateOverrideFile())
        .thenReturn(JfpUtilsTest.OVERRIDES_OLD_OBJECT_SAMPLE);
    OpenJdkController controller = new OpenJdkController(config);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
          || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
          || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
          || isJavaVersionAtLeast(17))) {
        assertEquals(
            true, Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")));
      }
    }
  }

  @Test
  public void testOldObjectSampleIsStillOverriddenThroughConfig() throws Exception {
    when(config.isProfilingHeapEnabled()).thenReturn(true);
    OpenJdkController controller = new OpenJdkController(config);
    try (final Recording recording =
        ((OpenJdkRecordingData) controller.createRecording(TEST_NAME).stop()).getRecording()) {
      assertEquals(
          Boolean.parseBoolean(recording.getSettings().get("jdk.OldObjectSample#enabled")), true);
    }
  }

  @Test
  public void testObjectAllocationIsDisabledOnUnsupportedVersion() throws Exception {
    OpenJdkController controller = new OpenJdkController(config);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!(isJavaVersionAtLeast(16))) {
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")));
        assertEquals(
            false,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")));
      }
    }
  }

  @Test
  public void testObjectAllocationIsStillOverriddenOnUnsupportedVersion() throws Exception {
    when(config.getProfilingTemplateOverrideFile())
        .thenReturn(JfpUtilsTest.OVERRIDES_OBJECT_ALLOCATION);
    OpenJdkController controller = new OpenJdkController(config);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!(isJavaVersionAtLeast(16))) {
        assertEquals(
            true,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")));
        assertEquals(
            true,
            Boolean.parseBoolean(
                recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")));
      }
    }
  }

  @Test
  public void testObjectAllocationIsStillOverriddenThroughConfig() throws Exception {
    when(config.isProfilingAllocationEnabled()).thenReturn(true);
    OpenJdkController controller = new OpenJdkController(config);
    try (final Recording recording =
        ((OpenJdkRecordingData) controller.createRecording(TEST_NAME).stop()).getRecording()) {
      assertEquals(
          Boolean.parseBoolean(
              recording.getSettings().get("jdk.ObjectAllocationInNewTLAB#enabled")),
          true);
      assertEquals(
          Boolean.parseBoolean(
              recording.getSettings().get("jdk.ObjectAllocationOutsideTLAB#enabled")),
          true);
    }
  }

  @Test
  public void testNativeMethodSampleIsDisabledOnUnsupportedVersion() throws Exception {
    OpenJdkController controller = new OpenJdkController(config);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 302)) || isJavaVersionAtLeast(11))) {
        assertEquals(
            false,
            Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
      }
    }
  }

  @Test
  public void testNativeMethodSampleIsStillOverriddenOnUnsupportedVersion() throws Exception {
    when(config.getProfilingTemplateOverrideFile())
        .thenReturn(JfpUtilsTest.OVERRIDES_NATIVE_METHOD_SAMPLE);
    OpenJdkController controller = new OpenJdkController(config);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertTrue(data instanceof OpenJdkRecordingData);
    try (final Recording recording = ((OpenJdkRecordingData) data).getRecording()) {
      if (!((isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 302)) || isJavaVersionAtLeast(11))) {
        assertEquals(
            true,
            Boolean.parseBoolean(recording.getSettings().get("jdk.NativeMethodSample#enabled")));
      }
    }
  }
}
